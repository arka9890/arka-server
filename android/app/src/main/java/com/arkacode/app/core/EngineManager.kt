package com.arkacode.app.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.arkacode.app.net.OpenCodeClient
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * Unduh + jalankan binary `opencode` resmi di dalam proot Alpine (F0).
 *
 * - Engine = opencode line **v2** (paket npm `@opencode/cli*`); API `/api/...`.
 * - Versi di-pin ([EngineConfig.VERSION]) demi build reproducible.
 * - Binary ditaruh di `<filesDir>/engine/opencode`, di-bind ke
 *   `/opt/arkacode` di dalam distro, dijalankan langsung (tanpa sh) agar
 *   sinyal stop bekerja.
 * - Server dipegang sebagai [Process] + log append ke `serve.log`.
 */
object EngineConfig {
    /** Versi engine yang dipin (line v2, dipublikasikan sebagai paket npm `@opencode/cli`). */
    const val VERSION = "2.0.31"
    const val DEFAULT_PORT = 4097
    const val DEFAULT_HOST = "127.0.0.1"

    /**
     * Integritas `sha512-<base64>` yang dipin dari npm untuk VERSION. Dipakai sebagai
     * fallback bila metadata registry tak terjangkau; bila ada yang lebih baru, integritas
     * tetap diambil dari registry saat resolusi rilis.
     */
    private val PINNED_INTEGRITY = mapOf(
        "@opencode/cli-linux-arm64-musl" to
            "sha512-SgmNyd7wIRpQ42xwJMFsNGk9H9nB3cp5MF5nhg+unnBWTRl9QuM7p+6mFBMvk/2lmyPq9oAt/oqWLvFM3i0GUQ==",
        "@opencode/cli-linux-arm64" to
            "sha512-LGAcODO4N9L+508pfVolMxPapGw+O3VG/Jo+1VlbuLC+RDH7IIX6Hi1bw5ofD4cfsC+zIJuZ/NF7cHPNSvH0mw==",
        "@opencode/cli-linux-x64-musl" to
            "sha512-Rum5c9g+UARVgm8Ydpv2qdruKrdgM0SARSFzt+rjoewR1I22ZMjPXVKSNo8FOhQvmubMSCbT6HB/UR/wZNqdZQ==",
        "@opencode/cli-linux-x64" to
            "sha512-RYbqR/HzKTH9OSh98VOt78bgQ8sgidUCCCKR9CABSc06AFxZdycezIg8dUmh+uBdp2mf4ckWVq9BbGblbxtFZQ==",
    )

    fun pinnedIntegrity(pkg: String): String? = PINNED_INTEGRITY[pkg]

    /** Paket kandidat (musl dulu) untuk ABI perangkat. */
    fun packages(abi: String): List<String> = EngineRelease.packageCandidates(abi)

    fun downloadUrl(pkg: String): String = EngineRelease.downloadUrl(VERSION, pkg)
}

sealed interface EngineState {
    data object NotInstalled : EngineState
    data class Downloading(val percent: Int) : EngineState
    data class Ready(val detail: String) : EngineState
    data class Running(val port: Int) : EngineState
    data class Error(val message: String) : EngineState
}

class EngineManager(private val context: Context) {
    private val distro = DistroManager(context)

    private val engineDir: File get() = File(context.filesDir, "engine").apply { mkdirs() }
    private val workspaceDir: File get() = File(context.filesDir, "workspace").apply { mkdirs() }

    fun binaryFile(): File = File(engineDir, "opencode")
    fun logFile(): File = File(engineDir, "serve.log")

    @Volatile
    private var serverProcess: Process? = null

    fun isDownloaded(): Boolean {
        val bin = binaryFile()
        return bin.isFile && bin.canExecute()
    }

    /**
     * Cek `--version` lewat proot DENGAN bind engine (`/opt/arkacode`).
     * Tanpa bind ini proot menjawab "not found" walau binary ada di host.
     * Return null bila output tidak mirip nomor versi (mis. pesan error shell).
     */
    suspend fun version(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val out = runOneShot(listOf("/opt/arkacode/opencode", "--version")).trim()
            // Output v2 bisa berbentuk "opencode v2.0.31"; ambil hanya inti semver-nya
            // agar tampilan & perbandingan versi konsisten.
            Regex("\\d+\\.\\d+\\.\\d+").find(out)?.value
                ?: Regex("\\d+\\.\\d+").find(out)?.value
        }.getOrNull()
    }

    /**
     * Binary opencode butuh simbol C++ (libstdc++/libgcc) yang tidak ada di
     * Alpine minirootfs polos — tanpa ini serve mati dengan "Error relocating".
     * Diinstal sekali via apk; marker di dalam rootfs sehingga ikut terhapus
     * (dan dipasang ulang) saat rootfs dipasang ulang.
     */
    suspend fun ensureGuestLibs(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val marker = File(distro.rootfsDir, "root/.guestlibs-ok")
            if (!marker.isFile) {
                val out = runOneShot(
                    listOf("/sbin/apk", "add", "--no-cache", "libstdc++", "libgcc"),
                    180_000,
                )
                if (out.contains("ERROR", ignoreCase = true)) {
                    throw IllegalStateException("apk gagal: ${out.trim().take(500)}")
                }
                runCatching { marker.writeText("ok") }
            }
        }
    }

    /**
     * Sebab proot/boot tidak bisa jalan. Dulu semua pelempar "Distro belum siap
     * (pasang Alpine dulu)" padahal binary proot yang hilang dari APK — menyesatkan.
     */
    private fun notReadyMessage(): String {
        val s = distro.status()
        return when {
            !s.prootAvailable || !s.loaderAvailable ->
                "Binary proot tidak ada di APK ini — build ulang APK dengan jaringan."
            !s.installed -> "Rootfs Alpine belum dipasang (unduh distro dulu)."
            else -> "Distro belum siap (pasang Alpine dulu)."
        }
    }

    /** Eksekusi satu perintah LANGSUNG (tanpa sh) di proot dengan bind engine + workspace. */
    suspend fun runOneShot(tail: List<String>, timeoutMs: Long = 30_000): String =
        withContext(Dispatchers.IO) {
            val argv = baseArgv() ?: throw IllegalStateException(notReadyMessage())
            val builder = ProcessBuilder(argv + guestEnvTail() + tail)
            builder.directory(context.filesDir)
            builder.environment().apply {
                putAll(distro.prootProcessEnv())
                this["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
                this["HOME"] = context.filesDir.absolutePath
                this["TMPDIR"] = context.cacheDir.absolutePath
            }
            builder.redirectErrorStream(true)
            val proc = builder.start()
            try {
                if (!proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    throw IllegalStateException("timeout")
                }
                proc.inputStream.readBytes().toString(Charsets.UTF_8).take(64 * 1024)
            } finally {
                runCatching { proc.destroyForcibly() }
            }
        }

    private fun baseArgv(): MutableList<String>? {
        val proot = distro.prootBinary() ?: return null
        val loader = distro.loaderBinary() ?: return null
        if (!distro.isInstalled()) return null
        val pw = workspaceDir
        return mutableListOf(
            proot.absolutePath,
            "--kill-on-exit",
            // SELinux Android melarang link() untuk app biasa; tanpa ini
            // apk add (ensureGuestLibs) gagal saat membuat hardlink.
            "--link2symlink",
            "-0",
            "-r", distro.rootfsDir.absolutePath,
            "-w", "/root/workspace",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", distro.rootfsDir.absolutePath + "/tmp:/dev/shm",
            "-b", "${pw.absolutePath}:/root/workspace",
            "-b", "${engineDir.absolutePath}:/opt/arkacode",
        )
    }

    private fun guestEnvTail(): List<String> {
        val loader = checkNotNull(distro.loaderBinary()) { "loader proot hilang" }
        return listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PROOT_LOADER=${loader.absolutePath}",
            "PROOT_TMP_DIR=${File(context.filesDir, "proot-tmp").apply { mkdirs() }.absolutePath}",
            "PROOT_NO_SECCOMP=1",
            "OPENCODE_DISABLE_AUTOUPDATE=1",
        )
    }

    /**
     * Unduh + verifikasi + ekstrak binary. [onProgress] 0..100.
     * Berhasil bila arsip valid, berisi entry `opencode`, dan `--version` jalan.
     */
    suspend fun ensureInstalled(onProgress: (Int) -> Unit = {}): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (isDownloaded()) {
                    return@runCatching version() ?: "terpasang (distro belum siap untuk cek versi)"
                }
                val archive = File(engineDir, "opencode.tgz")
                var downloaded = false
                var lastPkg: String? = null
                var lastError: Exception? = null
                for (pkg in EngineConfig.packages(distro.abi)) {
                    try {
                        onProgress(0)
                        lastPkg = pkg
                        download(EngineConfig.downloadUrl(pkg), archive, onProgress)
                        EngineConfig.pinnedIntegrity(pkg)?.let { expected ->
                            if (!sha512Base64(archive).equals(expected, ignoreCase = true)) {
                                throw IllegalStateException("Checksum $pkg tidak cocok (rusak/terpotong?)")
                            }
                        }
                        downloaded = true
                        break
                    } catch (e: Exception) {
                        lastError = e as? Exception ?: IllegalStateException(e.message)
                    }
                }
                if (!downloaded) throw lastError ?: IllegalStateException("Unduhan engine gagal")
                onProgress(95)
                extractBinary(archive)
                runCatching { File(engineDir, "asset.txt").writeText(lastPkg ?: "?") }
                archive.delete()
                onProgress(100)
                version() ?: "terpasang (distro belum siap untuk cek versi)"
            }
        }

    private fun download(url: String, target: File, onProgress: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "arkacode-android")
        }
        conn.connect()
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode} saat mengunduh engine")
        val total = conn.contentLengthLong.takeIf { it > 0 }
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(target).use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (total != null) onProgress(((read * 90) / total).toInt().coerceIn(0, 90))
                }
            }
        }
        if (target.length() < 1_000_000) throw IllegalStateException("Berkas unduhan terlalu kecil, kemungkinan bukan zip valid")
    }

    /** Hitung integritas npm `sha512-<base64>` untuk [file]. */
    private fun sha512Base64(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-512")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val b64 = android.util.Base64.encodeToString(md.digest(), android.util.Base64.NO_WRAP)
        return "sha512-$b64"
    }

    private fun extractBinary(tarGzFile: File) = extractBinaryTo(tarGzFile, binaryFile())

    private fun extractBinaryTo(tarGzFile: File, target: File) {
        var found = false
        GzipCompressorInputStream(BufferedInputStream(tarGzFile.inputStream())).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/')
                    if (name == "opencode") {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { o -> tar.copyTo(o) }
                        target.setExecutable(true, true)
                        found = true
                        break
                    }
                }
            }
        }
        if (!found) throw IllegalStateException("Arsip tidak berisi binary opencode")
        if (!target.canExecute()) throw IllegalStateException("Binary tidak bisa di-exec")
    }

    // --------------------------------------------------------- pembaruan (update)

    /** Hasil cek pembaruan: versi terpasang vs versi npm terbaru + integritas paket. */
    data class Update(
        val current: String?,
        val latest: String,
        val hasUpdate: Boolean,
        val integrities: Map<String, String?>,
    )

    /**
     * Cek versi terbaru opencode di npm (meta `@opencode/cli`). Integritas per paket
     * platform hanya diambil bila memang ada pembaruan. Null bila gagal jaringan.
     */
    suspend fun checkUpdate(): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val latest = EngineRelease.latestVersion(distro.abi) ?: return@runCatching null
            val current = version() ?: EngineConfig.VERSION
            val hasUpdate = EngineRelease.isNewer(latest, current)
            val integrities = if (hasUpdate) {
                runCatching { EngineRelease.latestRelease(distro.abi)?.integrity }.getOrNull() ?: emptyMap()
            } else {
                emptyMap()
            }
            Update(
                current = current,
                latest = latest,
                hasUpdate = hasUpdate,
                integrities = integrities,
            )
        }.getOrNull()
    }

    /**
     * Pasang rilis [version]: unduh paket cocok-ABI (musl, lalu gnu), verifikasi
     * integritas sha512 bila tersedia, ekstrak ke `opencode.new`, tukar atomik dengan
     * backup, lalu verifikasi `--version` = version. Menolak bila server sedang jalan.
     */
    private suspend fun installRelease(
        version: String,
        candidates: List<String>,
        integrities: Map<String, String?>,
        onProgress: (Int) -> Unit,
        onLog: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            check(!isRunning()) { "Server sedang jalan - hentikan dulu sebelum memperbarui." }
            val dl = File(engineDir, "opencode.download.tgz")
            val newBin = File(engineDir, "opencode.new")
            runCatching { dl.delete() }
            runCatching { newBin.delete() }

            var lastError: Exception? = null
            var used: String? = null
            for (pkg in candidates) {
                try {
                    onProgress(0)
                    onLog("mengunduh ${pkg.substringAfterLast('/')}...")
                    download(EngineRelease.downloadUrl(version, pkg), dl, onProgress)
                    val expected = integrities[pkg]
                    if (expected != null) {
                        val actual = sha512Base64(dl)
                        if (!actual.equals(expected, ignoreCase = true)) {
                            throw IllegalStateException("checksum $pkg tidak cocok")
                        }
                    } else {
                        onLog("catatan: integritas $pkg tak tersedia - verifikasi via --version.")
                    }
                    onProgress(92)
                    extractBinaryTo(dl, newBin)
                    used = pkg
                    break
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (used == null || !newBin.isFile) {
                throw lastError ?: IllegalStateException("Unduhan rilis gagal")
            }

            // Tukar atomik: lama -> .bak, baru -> opencode.
            val bin = binaryFile()
            val bak = File(engineDir, "opencode.bak")
            runCatching { bak.delete() }
            val hadOld = bin.isFile
            if (hadOld && !bin.renameTo(bak)) {
                throw IllegalStateException("gagal memindahkan binary lama")
            }
            if (!newBin.renameTo(bin)) {
                if (hadOld) runCatching { bak.renameTo(bin) }
                throw IllegalStateException("gagal memasang binary baru")
            }
            bin.setExecutable(true, true)

            // Pastikan lib C++ (libstdc++/libgcc) ada agar binary bisa `--version`
            // di distro yang baru dipasang (best-effort; marker membuatnya no-op).
            runCatching { ensureGuestLibs() }

            // Verifikasi: binary baru harus melaporkan versi = version.
            onProgress(97)
            val v = version()
            if (v == null || !EngineRelease.sameVersion(v, version)) {
                runCatching { bin.delete() }
                if (hadOld) runCatching { bak.renameTo(bin) }
                throw IllegalStateException("verifikasi gagal (--version: ${v ?: "tidak terbaca"})")
            }
            // .bak TIDAK dihapus: disimpan sebagai backup untuk rollback manual
            // dari tab Pengaturan (dipulihkan saat update berikutnya).
            runCatching { File(engineDir, "asset.txt").writeText(used) }
            runCatching { dl.delete() }
            runCatching { newBin.delete() }
            onProgress(100)
            v
        }
    }

    /** Wrapper publik untuk tab Pengaturan: cek pembaruan engine. */
    suspend fun checkEngineUpdate(): Update? = checkUpdate()

    /** Update manual (server harus mati; path sama dengan auto-update). */
    suspend fun updateEngineNow(
        onProgress: (Int) -> Unit = {},
        onLog: (String) -> Unit = {},
    ): Result<String> {
        val u = checkUpdate()
            ?: return Result.failure(IllegalStateException("cek pembaruan gagal (npm tak terjangkau?)"))
        if (!u.hasUpdate) return Result.success(u.current ?: EngineConfig.VERSION)
        return applyUpdate(u, onProgress, onLog)
    }

    /** Kembalikan binary dari backup `.bak` hasil update terakhir (server harus mati). */
    suspend fun rollbackUpdate(onLog: (String) -> Unit = {}): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            check(!isRunning()) { "Server sedang jalan - hentikan dulu sebelum rollback." }
            val bin = binaryFile()
            val bak = File(engineDir, "opencode.bak")
            check(bak.isFile) { "Tidak ada backup (.bak) untuk dikembalikan." }
            onLog("mengembalikan binary dari backup...")
            if (bin.isFile && !bin.delete()) throw IllegalStateException("gagal menghapus binary aktual")
            if (!bak.renameTo(bin)) throw IllegalStateException("gagal memulihkan backup")
            bin.setExecutable(true, true)
            val v = version()
            if (v == null) onLog("catatan: --version gagal - cek serve.log")
            onLog("versi sekarang: ${v ?: "tidak terbaca"}")
            v ?: ""
        }
    }

    /**
     * Unduh rilis terbaru bila tersedia; kalau tidak (offline/tak terbaca),
     * jatuh ke [ensureInstalled] (versi pin bawaan). Untuk instalasi pertama.
     */
    suspend fun installLatest(
        onProgress: (Int) -> Unit = {},
        onLog: (String) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        val rel = runCatching { EngineRelease.latestRelease(distro.abi) }.getOrNull()
        if (rel != null) {
            val r = installRelease(
                version = rel.version,
                candidates = EngineConfig.packages(distro.abi),
                integrities = rel.integrity,
                onProgress = onProgress,
                onLog = onLog,
            )
            if (r.isSuccess) return@withContext r
            onLog("unduh rilis terbaru gagal: ${r.exceptionOrNull()?.message ?: "?"}; coba versi bawaan.")
        } else {
            onLog("metadata npm tak terbaca (offline?) - pakai versi bawaan ${EngineConfig.VERSION}.")
        }
        ensureInstalled(onProgress)
    }

    /** Terapkan pembaruan hasil [checkUpdate] (unduh + verifikasi + tukar atomik). */
    suspend fun applyUpdate(
        update: Update,
        onProgress: (Int) -> Unit = {},
        onLog: (String) -> Unit = {},
    ): Result<String> = installRelease(
        version = update.latest,
        candidates = EngineConfig.packages(distro.abi),
        integrities = update.integrities,
        onProgress = onProgress,
        onLog = onLog,
    )

    fun distroStatus(): DistroStatus = distro.status()

    suspend fun setupDistro(onProgress: (Int, String) -> Unit = { _, _ -> }): Result<Unit> =
        distro.install(DistroManager.DEFAULT_ROOTFS_URL, onProgress)

    /**
     * Jalankan `opencode serve` sebagai proses persisten, lalu tunggu `/api/info`
     * (v2) OK (maks [waitMs]). Kembalikan port bila siap.
     */
    suspend fun startServer(
        port: Int = EngineConfig.DEFAULT_PORT,
        password: String,
        workDir: File = workspaceDir,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (serverProcess?.isAlive == true) return@runCatching port
            val bin = binaryFile()
            check(isDownloaded()) { "Engine belum siap (belum diunduh / file rusak). Unduh ulang." }
            // Reap sisa server lama (mis. proses yang selamat saat app di-restart / versi
            // engine baru baru saja dipasang) agar port tidak bentrok saat bind.
            reapStrayServers()
            ensureGuestLibs().getOrThrow()
            workDir.mkdirs()
            logFile().appendText("\n===== serve start port=$port =====\n")

            val argv = serveArgv(port, workDir, password, providerEnv())
                ?: throw IllegalStateException(notReadyMessage())
            val builder = ProcessBuilder(argv)
            builder.directory(context.filesDir)
            builder.environment().apply {
                putAll(distro.prootProcessEnv())
                put("OPENCODE_SERVER_PASSWORD", password)
                this["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
                this["HOME"] = context.filesDir.absolutePath
                this["TMPDIR"] = context.cacheDir.absolutePath
            }
            builder.redirectErrorStream(true)
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile()))
            val proc = builder.start()
            serverProcess = proc

            val client = OpenCodeClient(port = port, password = password)
            val deadline = System.currentTimeMillis() + 60_000
            var lastErr = ""
            while (System.currentTimeMillis() < deadline) {
                if (proc.isAlive.not()) {
                    serverProcess = null
                    val tail = tailLog(15).take(1200)
                    throw IllegalStateException(
                        "Proses serve mati saat startup." +
                            if (tail.isBlank()) " (serve.log kosong)" else "\n--- serve.log ---\n$tail",
                    )
                }
                try {
                    val h = client.health()
                    if (h.healthy) return@runCatching port
                    lastErr = "healthy=false"
                } catch (e: Exception) {
                    lastErr = e.message ?: "menunggu..."
                }
                delay(1000)
            }
            stopServer()
            throw IllegalStateException("Server tidak siap dalam 60 dtk ($lastErr). Cek serve.log.")
        }
    }

    fun stopServer() {
        runCatching { serverProcess?.destroy() }
        runCatching {
            serverProcess?.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        }
        runCatching { serverProcess?.destroyForcibly() }
        serverProcess = null
        runCatching { logFile().appendText("===== serve stop =====\n") }
    }

    /**
     * Cari proses `opencode serve` milik app ini di `/proc` yang bukan child kita
     * (sisa server yang tetap hidup setelah app di-restart / ganti binary), lalu
     * kirim SIGTERM. Aman: hanya cocok untuk cmdline yang memuat "opencode",
     * "serve", dan "--port"; proses lain (termasuk app) tak terpengaruh.
     */
    private fun reapStrayServers() {
        val me = android.os.Process.myPid()
        val stray = mutableListOf<Int>()
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            val cmd = runCatching { File(d, "cmdline").readBytes().toString(Charsets.UTF_8) }
                .getOrNull() ?: return@forEach
            if (cmd.contains("opencode") && cmd.contains("serve") && cmd.contains("--port")) {
                stray.add(pid)
            }
        }
        if (stray.isEmpty()) return
        stray.forEach { runCatching { android.os.Process.sendSignal(it, 15) } }
        runCatching { logFile().appendText("===== reap stray: ${stray.joinToString()} =====\n") }
        // Beri waktu port dibebaskan sebelum bind ulang.
        runCatching { Thread.sleep(600) }
        stray.forEach { runCatching { android.os.Process.sendSignal(it, 9) } }
    }

    /**
     * Uji eksekusi proot terisolasi (tanpa opencode): echo + arsitektur +
     * ukuran page kernel + isi /opt/arkacode. Untuk membedakan "proot tidak
     * bisa exec apa pun" vs "khusus binary opencode yang gagal".
     */
    suspend fun execSelfTest(): String = withContext(Dispatchers.IO) {
        val abi = android.os.Build.SUPPORTED_ABIS.joinToString(",")
        val echo = runCatching { runOneShot(listOf("/bin/echo", "PROOT_OK")) }.getOrElse { "echo GAGAL: ${it.message}" }
        val pagesize = runCatching { runOneShot(listOf("/bin/sh", "-c", "getconf PAGESIZE 2>/dev/null || echo unknown")) }.getOrElse { "?" }
        val bind = runCatching { runOneShot(listOf("/bin/ls", "-la", "/opt/arkacode")) }.getOrElse { "ls GAGAL: ${it.message}" }
        "ABI: $abi\n" + "echo: ${echo.trim()}\n" + "PAGESIZE: ${pagesize.trim()}\n" + "ls /opt/arkacode:\n${bind.trim().take(800)}"
    }

    fun downloadedAsset(): String? =
        File(engineDir, "asset.txt").takeIf { it.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() }

    fun isRunning(): Boolean = serverProcess?.isAlive == true

    fun tailLog(maxLines: Int = 30): String = runCatching {
        val lines = logFile().takeIf { it.isFile }?.readLines() ?: return ""
        lines.takeLast(maxLines).joinToString("\n")
    }.getOrDefault("")

    /**
     * Kosongkan log. Truncate (bukan delete): serve jalan memakai redirect
     * append (O_APPEND), jadi setelah dibersihkan baris baru tetap muncul —
     * sedangkan delete membuat proses menulis ke fd yatim yang tak terlihat.
     */
    fun clearLog() {
        runCatching {
            if (logFile().isFile) {
                java.io.RandomAccessFile(logFile(), "rw").setLength(0)
            }
        }
    }

    /**
     * argv proot untuk serve: cerminan DistroManager.buildProotCommand +
     * bind engine dir, eksekusi binary LANGSUNG (bukan sh -c) agar stop bersih.
     */
    /** Env API key provider tersimpan (mis. OPENAI_API_KEY) untuk proses serve. */
    private fun providerEnv(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        fun put(names: List<String>, stored: String) {
            val v = runCatching { SecureStore.getAuth(context, stored) }.getOrNull()
            if (!v.isNullOrBlank()) names.forEach { out[it] = v }
        }
        put(listOf("OPENAI_API_KEY"), "openai")
        put(listOf("OPENCODE_API_KEY"), "opencode")
        put(listOf("ANTHROPIC_API_KEY"), "anthropic")
        put(listOf("GOOGLE_GENERATIVE_AI_API_KEY", "GEMINI_API_KEY"), "google")
        put(listOf("GROQ_API_KEY"), "groq")
        put(listOf("OPENROUTER_API_KEY"), "openrouter")
        put(listOf("MISTRAL_API_KEY"), "mistral")
        put(listOf("DEEPSEEK_API_KEY"), "deepseek")
        put(listOf("XAI_API_KEY"), "xai")
        // Provider kustom OpenAI-compatible: opencode.json menunjuk {env:ARKACODE_<ID>_API_KEY}.
        runCatching {
            OpenCodeConfig.listCustom(context).forEach { p ->
                val v = SecureStore.getAuth(context, OpenCodeConfig.keyName(p.id))
                if (!v.isNullOrBlank()) out[OpenCodeConfig.envVar(p.id)] = v
            }
        }
        return out
    }

    private fun serveArgv(port: Int, workDir: File, password: String, extraEnv: Map<String, String> = emptyMap()): List<String>? {
        val proot = distro.prootBinary() ?: return null
        val loader = distro.loaderBinary() ?: return null
        if (!distro.isInstalled()) return null
        val pw = workspaceDir
        return mutableListOf(
            proot.absolutePath,
            "--kill-on-exit",
            // SELinux Android melarang link() untuk app biasa; tanpa ini
            // apk add (ensureGuestLibs) gagal saat membuat hardlink.
            "--link2symlink",
            "-0",
            "-r", distro.rootfsDir.absolutePath,
            "-w", "/root/workspace",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", distro.rootfsDir.absolutePath + "/tmp:/dev/shm",
            "-b", "${pw.absolutePath}:/root/workspace",
            "-b", "${engineDir.absolutePath}:/opt/arkacode",
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PROOT_LOADER=${loader.absolutePath}",
            "PROOT_TMP_DIR=${File(context.filesDir, "proot-tmp").apply { mkdirs() }.absolutePath}",
            "PROOT_NO_SECCOMP=1",
            "OPENCODE_DISABLE_AUTOUPDATE=1",
            "OPENCODE_SERVER_PASSWORD=$password",
        ) + extraEnv.map { (k, v) -> "$k=$v" } + listOf(
            "/opt/arkacode/opencode",
            "serve",
            "--port", port.toString(),
            // Bind ke semua interface: web UI + API opencode dijangkau lewat
            // Tailscale (IP tailscale0) dari device lain. Password WAJIB kuat.
            "--hostname", "0.0.0.0",
        )
    }
}
