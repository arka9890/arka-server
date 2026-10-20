package com.arkacode.app.net

/** Sesi chat di server (parsing longgar — field tak dikenal diabaikan). */
data class ChatSession(
    val id: String,
    val title: String,
    val providerID: String? = null,
    val modelID: String? = null,
    /** Agent yang di-set eksplisit di sesi ini (null = belum pernah disetel). */
    val agentID: String? = null,
)

/** Satu tool-call dalam balasan assistant. */
data class ToolUse(val id: String, val name: String, val status: String)

/** Item chat siap-render. */
sealed interface ChatItem {
    val id: String

    data class User(override val id: String, val text: String, val fileCount: Int) : ChatItem
    data class Assistant(
        override val id: String,
        val texts: List<String>,
        val reasonings: List<String>,
        val tools: List<ToolUse>,
        val finish: String? = null,
        val error: String? = null,
    ) : ChatItem

    /** synthetic/system/dll — tampil kecil. */
    data class Note(override val id: String, val text: String) : ChatItem
}

data class ModelInfo(
    val id: String,
    val name: String,
    val providerID: String,
    val free: Boolean = false,
    /** Jenis API model di server (`aisdk` didukung; `native` tak didukung engine). */
    val apiType: String? = null,
    /** `active` | `deprecated` (bila ada). */
    val status: String? = null,
)

/** Agent opencode (mode primary/subagent/all). */
data class AgentInfo(
    val id: String,
    val description: String,
    val mode: String,
    val color: String?,
    val hidden: Boolean,
)

/** Perintah (slash command) yang terdaftar di server. */
data class CommandInfo(
    val name: String,
    val description: String,
    val agent: String?,
    /** Template body; di-expand klien karena server tak meng-expand slash. */
    val template: String? = null,
    val model: String? = null,
    val subtask: Boolean = false,
)

/** Skill opencode (folder/URL/embedded). */
data class SkillInfo(
    val name: String,
    val description: String,
    val location: String,
    val slash: Boolean,
    /** Isi skill (instruksi) — dikirim sebagai prompt saat "run". */
    val content: String? = null,
)

/** Satu berkas dalam rencana revert. */
data class FileDiffEntry(
    val path: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val patch: String,
)

/** Status revert yang distage server. */
data class RevertState(val messageID: String, val files: List<FileDiffEntry>)
data class ProviderInfo(val id: String, val name: String, val models: List<ModelInfo>)
data class ModelRef(val providerID: String, val id: String)

/** Permintaan persetujuan (ringkasan best-effort dari field mentahnya). */
data class PermissionReq(val id: String, val summary: String)

/** Satu opsi jawaban untuk pertanyaan agent (form field). */
data class QuestionOption(
    val label: String,
    val description: String,
    /** Nilai yang dikirim ke server (`Form.Option.value`); default = label. */
    val value: String = label,
)

/** Satu pertanyaan dari agent (dipetakan dari `Form.Field` v2). */
data class QuestionInfo(
    val header: String,
    val question: String,
    val options: List<QuestionOption>,
    val multiple: Boolean,
    val custom: Boolean,
    /** Kunci field form v2 (dipakai saat mengirim jawaban). */
    val key: String = "",
    /** Tipe field v2: string|number|integer|boolean|multiselect|external. */
    val type: String = "string",
)

/** Permintaan pertanyaan yang menunggu jawaban user. */
data class QuestionRequest(val id: String, val questions: List<QuestionInfo>)

/** Entri filesystem server (path relatif ke working dir). */
data class FsEntry(val path: String, val isDir: Boolean)


// ----------------------------------------------------- SSE events (semantik)

/**
 * Event semantik dari SSE `/api/event` (v1.19: `{type, data}`; toleran ke
 * `properties` legacy). Dipakai ChatScreen untuk update inkremental tanpa
 * selalu re-fetch.
 */
sealed interface ServerEvent {
    val sessionID: String?

    object Connected : ServerEvent {
        override val sessionID: String? get() = null
    }

    /** Status sesi: `idle` | `busy` | `retry`. */
    data class Status(override val sessionID: String?, val status: String, val message: String?) : ServerEvent

    /** Potongan teks assistant (streaming). */
    data class TextDelta(override val sessionID: String?, val messageID: String?, val delta: String) : ServerEvent

    /** Teks assistant final (nilai penuh). */
    data class TextEnded(override val sessionID: String?, val messageID: String?, val text: String) : ServerEvent

    /** Potongan reasoning (streaming). */
    data class ReasoningDelta(override val sessionID: String?, val messageID: String?, val delta: String) : ServerEvent

    /** Perubahan status tool (label/id boleh kosong pada event legacy). */
    data class Tool(
        override val sessionID: String?,
        val id: String,
        val label: String,
        val status: String,
    ) : ServerEvent

    /** Persetujuan muncul/selesai. */
    data class Permission(override val sessionID: String?) : ServerEvent

    /** Pertanyaan agent (question tool) muncul/selesai. */
    data class Question(override val sessionID: String?) : ServerEvent

    /** Error sesi. */
    data class Error(override val sessionID: String?, val message: String) : ServerEvent

    /** Perubahan lain yang butuh refresh (pesan/part/diff/sesi). */
    data class Other(override val sessionID: String?, val type: String) : ServerEvent
}

// ------------------------------------------------------------- integrations

/** Satu opsi prompt select. */
data class IntegrationOption(val label: String, val value: String, val hint: String?)

/** Prompt input saat memulai OAuth. */
data class IntegrationPrompt(
    val type: String,
    val key: String,
    val message: String,
    val placeholder: String?,
    val options: List<IntegrationOption>,
    val whenKey: String?,
    val whenOp: String?,
    val whenValue: String?,
    // Field `external` (gcloud/AWS profile) butuh default terisi dan wajib diisi
    // sebelum dikirim; server juga menolak dengan 400 bila kosong.
    val default: String? = null,
    val required: Boolean = false,
)

/** Metode autentikasi integrasi (oauth|key|env|external). */
data class IntegrationMethod(
    val id: String?,
    val type: String,
    val label: String,
    val prompts: List<IntegrationPrompt>,
    val envNames: List<String>,
)

/**
 * Koneksi integrasi yang sudah ada (credential|env).
 *
 * [id] adalah credential ID untuk tipe `credential` — inilah yang di-DELETE oleh
 * [OpenCodeClient.removeCredential]. [method] (key|oauth|external) dan [needsAuth]
 * dibaca dari `status.status` untuk menandai koneksi yang harus di-reconnect.
 */
data class IntegrationConnection(
    val type: String,
    val id: String?,
    val label: String?,
    val envName: String?,
    val method: String? = null,
    val needsAuth: Boolean = false,
)

/** Integrasi + metode & koneksi. */
data class IntegrationInfo(
    val id: String,
    val name: String,
    val methods: List<IntegrationMethod>,
    val connections: List<IntegrationConnection>,
)

/** Attempt OAuth yang baru dimulai. */
data class OAuthAttempt(val attemptID: String, val url: String, val instructions: String, val mode: String)

/** Status attempt OAuth. */
data class OAuthStatus(val status: String, val message: String?)
