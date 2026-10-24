package com.arkacode.app.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Rahasia app (password server opencode, port) di penyimpanan terenkripsi.
 * Tidak pernah di-log atau ditampilkan mentah selain kolom password UI.
 */
object SecureStore {
    private const val FILE = "arkacode_secrets"

    private fun prefs(context: Context) = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Pair(port, password); null bila belum pernah disimpan. */
    fun load(context: Context): Pair<Int, String>? = runCatching {
        val p = prefs(context)
        if (!p.contains("server_port") && !p.contains("server_password")) return null
        Pair(p.getInt("server_port", 4096), p.getString("server_password", "") ?: "")
    }.getOrNull()

    fun save(context: Context, port: Int, password: String) {
        runCatching {
            prefs(context).edit()
                .putInt("server_port", port)
                .putString("server_password", password)
                .apply()
        }
    }

    /** API key provider (mis. "openai"); null bila belum disimpan. */
    fun getAuth(context: Context, name: String): String? = runCatching {
        prefs(context).getString("provider_key_$name", null)
    }.getOrNull()

    /** Kosong = hapus. */
    fun setAuth(context: Context, name: String, value: String) {
        runCatching {
            val e = prefs(context).edit()
            if (value.isBlank()) e.remove("provider_key_$name")
            else e.putString("provider_key_$name", value.trim())
            e.apply()
        }
    }

    /** Flag boolean app (autoboot, auto-update). Zero code path baru: sama prefs terenkripsi. */
    fun getBool(context: Context, name: String, default: Boolean): Boolean = runCatching {
        prefs(context).getBoolean(name, default)
    }.getOrDefault(default)

    fun setBool(context: Context, name: String, value: Boolean) {
        runCatching { prefs(context).edit().putBoolean(name, value).apply() }
    }

    /** GitHub Personal Access Token (tersimpan terenkripsi). */
    fun getGitPat(context: Context): String? = runCatching {
        prefs(context).getString("git_pat", null)
    }.getOrNull()

    fun setGitPat(context: Context, value: String) {
        runCatching {
            val e = prefs(context).edit()
            if (value.isBlank()) e.remove("git_pat")
            else e.putString("git_pat", value.trim())
            e.apply()
        }
    }

    // ---- OpenAI-compatible Bridge Settings ----
    const val DEFAULT_BRIDGE_PORT = 4098

    fun getBridgePort(context: Context): Int = runCatching {
        prefs(context).getInt("bridge_port", DEFAULT_BRIDGE_PORT)
    }.getOrDefault(DEFAULT_BRIDGE_PORT)

    fun setBridgePort(context: Context, port: Int) {
        runCatching { prefs(context).edit().putInt("bridge_port", port).apply() }
    }

    fun isBridgeEnabled(context: Context): Boolean = runCatching {
        prefs(context).getBoolean("bridge_enabled", true)
    }.getOrDefault(true)

    fun setBridgeEnabled(context: Context, enabled: Boolean) {
        runCatching { prefs(context).edit().putBoolean("bridge_enabled", enabled).apply() }
    }
}
