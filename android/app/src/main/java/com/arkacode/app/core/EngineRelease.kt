package com.arkacode.app.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolusi rilis `opencode` di registri npm untuk fitur pembaruan otomatis (F-update).
 *
 * Kenapa npm, bukan GitHub releases?
 *  - Rilis GitHub `anomalyco/opencode` mengikuti line **v1.19.x** (API `/global/health`,
 *    model ber-`api.type`). Line **v2.0.x** dipublikasikan sebagai paket npm
 *    `@opencode/cli*` (API `/api/info`, model ai-sdk bundled). App memakai v2.
 *  - Binary dijalankan di dalam proot dan di-bind ke `/opt/arkacode`; auto-update
 *    bawaan tetap dimatikan (OPENCODE_DISABLE_AUTOUPDATE=1) dan dikelola modul ini.
 *
 * Sumber data (registry npm, tanpa auth):
 *  - Versi terbaru: `GET https://registry.npmjs.org/@opencode/cli/latest` → `version`
 *    (semua paket platform dirilis lockstep).
 *  - Integritas + tarball per paket platform: `GET .../<pkg>/<version>` →
 *    `dist.integrity` (format `sha512-<base64>`) dan `dist.tarball`.
 */
object EngineRelease {
    const val REGISTRY = "https://registry.npmjs.org"
    const val SCOPE = "@opencode"

    /** Paket utama (meta) — dipakai untuk resolve versi terbaru. */
    const val META_PKG = "$SCOPE/cli"

    private const val UA = "arkacode-android"

    private val json = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------- versioning

    /** Ekstrak inti versi numerik: "v2.0.31" / "opencode 2.0.31" → [2, 0, 31]. */
    fun versionCore(v: String): List<Int> =
        Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

    /** true bila [latest] lebih baru dari [installed] (installed null → dianggap perlu update). */
    fun isNewer(latest: String, installed: String?): Boolean {
        val a = versionCore(latest)
        val b = installed?.let { versionCore(it) } ?: return a.isNotEmpty()
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** true bila dua string menunjuk versi numerik yang sama (versi vs output --version). */
    fun sameVersion(a: String, b: String): Boolean =
        versionCore(a) == versionCore(b) && versionCore(a).isNotEmpty()

    /** Rapikan versi jadi berawalan "v" (mis. "2.0.31" → "v2.0.31"). */
    fun normalizeTag(tag: String): String {
        val t = tag.trim().trim('/')
        return if (t.startsWith("v")) t else "v$t"
    }

    /**
     * Versi tanpa awalan "v" untuk tampilan maupun URL npm. Menjaga agar nilai dari
     * tag ("v2.0.31") maupun dari `--version` ("2.0.31") konsisten.
     */
    fun bare(v: String): String {
        val t = v.trim()
        return if (t.startsWith("v") || t.startsWith("V")) t.substring(1) else t
    }

    // ------------------------------------------------------------ paket / ABI

    /**
     * Kandidat nama paket npm berurutan (musl dulu karena Alpine, lalu gnu) untuk ABI
     * perangkat. `armeabi-v7a` (32-bit) tidak disediakan upstream → fallback arm64.
     */
    fun packageCandidates(abi: String): List<String> {
        val a = abi.lowercase()
        return when {
            a.startsWith("arm64") || a.contains("aarch64") -> listOf(
                "$SCOPE/cli-linux-arm64-musl",
                "$SCOPE/cli-linux-arm64",
            )
            a.startsWith("x86_64") || a.startsWith("x64") || a.contains("amd64") -> listOf(
                "$SCOPE/cli-linux-x64-musl",
                "$SCOPE/cli-linux-x64",
                "$SCOPE/cli-linux-x64-baseline-musl",
                "$SCOPE/cli-linux-x64-baseline",
            )
            else -> listOf(
                "$SCOPE/cli-linux-arm64-musl",
                "$SCOPE/cli-linux-arm64",
            )
        }
    }

    /**
     * URL tarball npm untuk [pkg] pada [version]:
     * `https://registry.npmjs.org/@opencode/<base>/-/<base>-<ver>.tgz`.
     */
    fun downloadUrl(version: String, pkg: String): String {
        val base = pkg.substringAfterLast('/')
        return "$REGISTRY/$SCOPE/$base/-/$base-${bare(version)}.tgz"
    }

    // -------------------------------------------------------------- networking

    /** Metadata satu versi paket npm. */
    data class PkgVersion(val version: String, val integrity: String?, val shasum: String?)

    /**
     * Ambil metadata paket [pkg]. [version] "latest" memakai dist-tag terbaru.
     * Null bila offline / paket/versi tak ada.
     */
    fun packageMeta(pkg: String, version: String = "latest", timeoutMs: Int = 20_000): PkgVersion? {
        val v = if (version == "latest") "latest" else bare(version)
        val conn = (URL("$REGISTRY/$pkg/$v").openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.readBytes().toString(Charsets.UTF_8)
            val root = json.parseToJsonElement(text) as? JsonObject ?: return null
            val ver = (root["version"] as? JsonPrimitive)?.contentOrNull ?: return null
            val dist = root["dist"] as? JsonObject
            PkgVersion(
                version = ver,
                integrity = (dist?.get("integrity") as? JsonPrimitive)?.contentOrNull,
                shasum = (dist?.get("shasum") as? JsonPrimitive)?.contentOrNull,
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * Versi terbaru opencode. Utamakan meta paket `@opencode/cli`, lalu fallback ke
     * paket platform [abi] (mis. bila meta tak tersedia). Null bila tak terbaca.
     */
    fun latestVersion(abi: String? = null, timeoutMs: Int = 20_000): String? {
        packageMeta(META_PKG, "latest", timeoutMs)?.version?.let { return it }
        val pkgs = abi?.let { packageCandidates(it) } ?: emptyList()
        for (pkg in pkgs) {
            packageMeta(pkg, "latest", timeoutMs)?.version?.let { return it }
        }
        return null
    }

    /** Rilis: versi + peta integritas per paket kandidat ABI. */
    data class Release(val version: String, val integrity: Map<String, String?>)

    /**
     * Resolusi penuh (versi terbaru + integritas tiap paket kandidat ABI).
     * Null bila versi terbaru tak terbaca.
     */
    fun latestRelease(abi: String, timeoutMs: Int = 20_000): Release? {
        val version = latestVersion(abi, timeoutMs) ?: return null
        val map = LinkedHashMap<String, String?>()
        for (pkg in packageCandidates(abi)) {
            map[pkg] = packageMeta(pkg, version, timeoutMs)?.integrity
        }
        return Release(version, map)
    }
}
