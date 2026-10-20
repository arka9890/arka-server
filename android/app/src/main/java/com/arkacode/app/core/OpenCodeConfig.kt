package com.arkacode.app.core

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Provider kustom "OpenAI-compatible" (npm `@ai-sdk/openai-compatible`) yang
 * ditulis ke `opencode.json` global di dalam distro proot:
 * `<rootfs>/root/.config/opencode/opencode.json` (HOME=/root).
 *
 * Hanya menyentuh subtree `provider` — kunci opencode.json lain milik user
 * dibiarkan utuh (merge, bukan timpa). API key tidak pernah ditulis plaintext:
 * nilai `options.apiKey` memakai substitusi `{env:VAR}` (didukung opencode),
 * dan nilainya disuntik sebagai env saat serve nyala (lihat EngineManager).
 */
object OpenCodeConfig {
    const val NPM = "@ai-sdk/openai-compatible"
    private const val SCHEMA = "https://opencode.ai/config.json"
    private const val CONFIG_REL = "root/.config/opencode/opencode.json"

    private val json = Json { prettyPrint = true }

    /** Provider bawaan opencode — cegah id provider kustom menimpanya. */
    private val BUILTIN_IDS = setOf(
        "opencode", "openai", "anthropic", "google",
        "groq", "openrouter", "mistral", "deepseek", "xai",
    )

    data class CustomProvider(
        val id: String,
        val name: String,
        val baseUrl: String,
        val models: List<String>,
        val hasKey: Boolean,
    )

    private fun rootfsDir(context: Context): File =
        File(File(context.filesDir, "distro"), "alpine")

    fun configFile(context: Context): File = File(rootfsDir(context), CONFIG_REL)

    /** True bila rootsf Alpine sudah terpasang (tanpa itu tak ada tempat menulis config). */
    fun isAvailable(context: Context): Boolean =
        File(rootfsDir(context), "etc/alpine-release").isFile ||
            File(rootfsDir(context), "bin/busybox").isFile

    /** Nama env deterministik tempat menyuntik API key provider kustom. */
    fun envVar(id: String): String =
        "ARKACODE_" + id.uppercase().replace(Regex("[^A-Z0-9]"), "_") + "_API_KEY"

    /** Nama kunci penyimpanan terenkripsi untuk provider kustom. */
    fun keyName(id: String): String = "custom:$id"

    /** ID unik (slug) dari nama; dibedakan dari [existing] dengan sufiks angka. */
    fun makeId(name: String, existing: Set<String>): String {
        val base = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "custom" }
        if (base !in existing) return base
        var n = 2
        while ("$base-$n" in existing) n++
        return "$base-$n"
    }

    /**
     * `null` = berkas ada tapi tidak bisa diparse (JSON tidak valid). Pemanggil
     * yang menulis HARUS membatalkan agar config user tidak tertimpa.
     */
    private fun readRoot(context: Context): JsonObject? {
        val f = configFile(context)
        if (!f.isFile) return JsonObject(emptyMap())
        val raw = runCatching { f.readText() }.getOrDefault("")
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
    }

    /** Id yang tak boleh dipakai provider kustom (bawaan opencode + yang ada di config). */
    fun existingIds(context: Context): Set<String> {
        val fromConfig = (readRoot(context)?.get("provider") as? JsonObject)?.keys.orEmpty()
        return fromConfig + BUILTIN_IDS
    }

    /** Daftar provider kustom (npm openai-compatible) yang tertulis di config. */
    fun listCustom(context: Context): List<CustomProvider> {
        val providers = readRoot(context)?.get("provider") as? JsonObject ?: return emptyList()
        return providers.entries.mapNotNull { (id, el) ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if ((o["npm"] as? JsonPrimitive)?.contentOrNull != NPM) return@mapNotNull null
            val opts = o["options"] as? JsonObject
            val models = (o["models"] as? JsonObject)?.keys?.toList() ?: emptyList()
            CustomProvider(
                id = id,
                name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: id,
                baseUrl = (opts?.get("baseURL") as? JsonPrimitive)?.contentOrNull ?: "",
                models = models,
                hasKey = !SecureStore.getAuth(context, keyName(id)).isNullOrBlank(),
            )
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * Tambah/perbarui provider kustom [id].
     * [newKey] non-blank = ganti API key; null/blank = pertahankan yang lama.
     * [deleteKey] true = hapus API key tersimpan.
     */
    fun upsert(
        context: Context,
        id: String,
        name: String,
        baseUrl: String,
        models: List<String>,
        newKey: String? = null,
        deleteKey: Boolean = false,
    ): Boolean {
        val current = readRoot(context) ?: return false
        when {
            deleteKey -> SecureStore.setAuth(context, keyName(id), "")
            !newKey.isNullOrBlank() -> SecureStore.setAuth(context, keyName(id), newKey)
        }
        val hasKey = !SecureStore.getAuth(context, keyName(id)).isNullOrBlank()

        val root = current.toMutableMap()
        val providers = ((root["provider"] as? JsonObject) ?: JsonObject(emptyMap())).toMutableMap()

        val modelMap = buildJsonObject {
            models.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { m ->
                put(m, buildJsonObject { put("name", JsonPrimitive(m)) })
            }
        }
        val opts = buildJsonObject {
            put("baseURL", JsonPrimitive(baseUrl.trim()))
            if (hasKey) put("apiKey", JsonPrimitive("{env:${envVar(id)}}"))
        }
        providers[id] = buildJsonObject {
            put("npm", JsonPrimitive(NPM))
            put("name", JsonPrimitive(name.trim().ifBlank { id }))
            put("options", opts)
            put("models", modelMap)
        }
        root["provider"] = JsonObject(providers)
        if (root["\$schema"] == null) root["\$schema"] = JsonPrimitive(SCHEMA)
        return write(context, JsonObject(root))
    }

    /** Hapus provider kustom + API key tersimpannya. @return false bila gagal. */
    fun remove(context: Context, id: String): Boolean {
        val current = readRoot(context) ?: return false
        SecureStore.setAuth(context, keyName(id), "")
        val root = current.toMutableMap()
        val providers = ((root["provider"] as? JsonObject) ?: return true).toMutableMap()
        providers.remove(id)
        if (providers.isEmpty()) root.remove("provider") else root["provider"] = JsonObject(providers)
        return write(context, JsonObject(root))
    }

    private fun write(context: Context, root: JsonObject): Boolean = runCatching {
        val f = configFile(context)
        f.parentFile?.mkdirs()
        f.writeText(json.encodeToString(JsonObject.serializer(), root) + "\n")
        true
    }.getOrDefault(false)
}
