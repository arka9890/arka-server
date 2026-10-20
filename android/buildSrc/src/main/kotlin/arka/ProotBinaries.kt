package arka

import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Menyiapkan binary proot Android (dari paket Termux) ke sebuah folder jniLibs.
 *
 * Kenapa harus di dalam APK? Sejak Android 10 (targetSdk 29+) aplikasi tidak
 * boleh mengeksekusi file dari folder datanya sendiri (kebijakan W^X / SELinux),
 * sedangkan folder nativeLibraryDir boleh. Karena itu proot + loader + library
 * pendukungnya harus dibundel sebagai `lib*.so` di dalam APK.
 *
 * Logika ini sengaja ditaruh di buildSrc (Kotlin biasa) bukan di
 * app/build.gradle.kts, supaya bisa dites dan tidak bertabrakan dengan
 * keterbatasan compiler Kotlin-DSL untuk closure bersarang.
 */
object ProotBinaries {

    /**
     * Versi paket Termux yang dibundel.
     *
     * Catatan: `proot` harus mengikuti rilis Termux. Paket versi lama dihapus
     * dari mirror begitu rilis baru keluar (mis. 5.1.107.96 hilang saat
     * 5.1.107.97 rilis), sehingga build yang memakai versi lama akan 404
     * diam-diam dan APK terbit tanpa binary proot.
     */
    const val PROOT_VERSION = "5.1.107.97"
    const val TALLOC_VERSION = "2.5.0"
    const val SHMEM_VERSION = "0.7"

    /**
     * Gabungan semua versi paket. Dipakai sebagai input Gradle dan isi penanda
     * `outputDir/.proot-version`, supaya perubahan versi benar-benar
     * memaksa unduhan ulang (bukan cuma mengubah konstanta).
     */
    val VERSIONS = "$PROOT_VERSION-$TALLOC_VERSION-$SHMEM_VERSION"

    /** Mirror paket Termux (semua sudah diverifikasi punya paket proot terbaru). */
    val MIRRORS = listOf(
        "https://packages-cf.termux.dev/apt/termux-main",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
        "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
        "https://mirrors.bfsu.edu.cn/termux/apt/termux-main",
    )

    val ABI_TO_ARCH = listOf(
        "arm64-v8a" to "aarch64",
        "armeabi-v7a" to "arm",
        "x86_64" to "x86_64",
        "x86" to "i686",
    )

    private data class Package(
        val name: String,
        val path: String,
        /** entri di dalam data.tar -> nama file di jniLibs */
        val files: Map<String, String>,
    )

    private val packages = listOf(
        Package(
            name = "proot",
            path = "pool/main/p/proot/proot_${PROOT_VERSION}_{arch}.deb",
            files = mapOf(
                "/usr/bin/proot" to "libproot.so",
                "/usr/libexec/proot/loader" to "libproot-loader.so",
            ),
        ),
        Package(
            name = "libtalloc",
            path = "pool/main/libt/libtalloc/libtalloc_${TALLOC_VERSION}_{arch}.deb",
            files = mapOf("/usr/lib/libtalloc.so.${TALLOC_VERSION}" to "libtalloc.so"),
        ),
        Package(
            name = "libandroid-shmem",
            path = "pool/main/liba/libandroid-shmem/libandroid-shmem_${SHMEM_VERSION}_{arch}.deb",
            files = mapOf("/usr/lib/libandroid-shmem.so" to "libandroid-shmem.so"),
        ),
    )

    /**
     * @return jumlah ABI yang berhasil disiapkan (0 = tidak ada, build tetap lanjut).
     */
    fun prepare(outputDir: File, cacheDir: File, log: (String) -> Unit): Int {
        outputDir.mkdirs()
        cacheDir.mkdirs()

        // Binary hasil ekstraksi versi lain (mis. cache lama 5.1.107.96) harus
        // dibuang dulu, jangan dipakai ulang diam-diam di bawah label versi baru.
        val marker = File(outputDir, ".proot-version")
        val cachedVersion = if (marker.exists()) marker.readText().trim() else null
        if (cachedVersion != VERSIONS) {
            outputDir.listFiles()?.forEach { child ->
                if (child == marker) return@forEach
                child.deleteRecursively()
            }
            log("[proot] versi berubah ($cachedVersion -> $VERSIONS), hasil ekstraksi lama dibersihkan")
        }

        var readyAbis = 0

        for ((abi, arch) in ABI_TO_ARCH) {
            val abiDir = File(outputDir, abi)
            if (File(abiDir, "libproot.so").exists() && File(abiDir, "libproot-loader.so").exists()) {
                log("[proot] $abi sudah ada (cache) — dilewati")
                readyAbis++
                continue
            }
            var abiOk = true
            for (pkg in packages) {
                // Nama file cache sengaja memuat versi, supaya cache .96 tidak
                // diekstrak ulang setelah pin naik ke .97.
                val deb = File(cacheDir, pkg.path.substringAfterLast('/').replace("{arch}", arch))
                if (!deb.exists()) {
                    val relative = pkg.path.replace("{arch}", arch)
                    var downloaded = false
                    for (mirror in MIRRORS) {
                        val url = "$mirror/$relative"
                        if (download(url, deb, log)) {
                            downloaded = true
                            break
                        }
                    }
                    if (!downloaded) {
                        log("[proot] gagal mengunduh paket ${pkg.name} untuk $abi")
                        abiOk = false
                        break
                    }
                }
                for ((entry, libName) in pkg.files) {
                    val target = File(abiDir, libName)
                    val extracted = extractEntry(deb, entry, target)
                    if (extracted) {
                        target.setExecutable(true, false)
                        log("[proot] $abi/${libName} ← ${pkg.name} (${target.length()} byte)")
                    } else {
                        log("[proot] entri $entry tidak ditemukan di ${pkg.name}")
                        abiOk = false
                    }
                }
            }
            if (abiOk && File(abiDir, "libproot.so").exists()) readyAbis++
        }

        // Hanya tulis penanda kalau ada yang benar-benar siap; kalau gagal
        // semua (offline), build berikutnya tetap mencoba ulang.
        if (readyAbis > 0) marker.writeText(VERSIONS)
        return readyAbis
    }

    private fun download(url: String, target: File, log: (String) -> Unit): Boolean {
        return try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "arka-gradle")
            try {
                if (conn.responseCode !in 200..299) {
                    log("[proot] HTTP ${conn.responseCode} untuk $url")
                    return false
                }
                conn.inputStream.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
                true
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            log("[proot] gagal unduh $url (${e.message})")
            false
        }
    }

    /** Ambil satu file dari `data.tar.*` di dalam paket .deb. */
    private fun extractEntry(deb: File, entrySuffix: String, target: File): Boolean {
        val wanted = entrySuffix.replace('\\', '/').trimStart('/')
        var found = false
        try {
            ArArchiveInputStream(deb.inputStream().buffered()).use { ar ->
                var dataStream: InputStream? = null
                while (!found) {
                    val arEntry = ar.nextArEntry ?: break
                    val name = arEntry.name.trimEnd('/')
                    if (!name.startsWith("data.tar")) continue
                    dataStream = when {
                        name.endsWith(".xz") -> XZCompressorInputStream(ar)
                        name.endsWith(".gz") -> GzipCompressorInputStream(ar)
                        else -> ar
                    }
                    found = scanTar(dataStream, wanted, target)
                }
            }
        } catch (e: Exception) {
            return false
        }
        return found
    }

    private fun scanTar(stream: InputStream, wantedSuffix: String, target: File): Boolean {
        TarArchiveInputStream(stream).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: return false
                if (!entry.isFile) continue
                val normalized = entry.name.removePrefix("./").trimStart('/')
                if (!normalized.endsWith(wantedSuffix)) {
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> tar.copyTo(out) }
                return true
            }
        }
    }
}
