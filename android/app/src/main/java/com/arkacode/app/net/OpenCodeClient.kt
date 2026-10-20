package com.arkacode.app.net

import com.arkacode.app.core.Attachment
import com.arkacode.app.core.normalizeFsPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

data class ServerHealth(val healthy: Boolean, val version: String)

/**
 * Klien HTTP untuk `opencode serve` line **v2** (`/api/...`, engine npm `@opencode/cli`).
 * Semua panggilan cancellable (Stop/abort benar-benar memutus HTTP).
 * Parsing longgar: field tak dikenal diabaikan, bentuk tak dikenal → dilewati.
 */
class OpenCodeClient(
    port: Int = 4096,
    private val username: String = "opencode",
    private val password: String = "",
) {
    private val baseUrl = "http://127.0.0.1:$port"
    private val json = Json { ignoreUnknownKeys = true }
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Client sabar untuk long-poll `wait` (idle bisa makan waktu menit). */
    private val longClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    private fun authHeader(): String = Credentials.basic(username, password)

    // ------------------------------------------------------------- transport

    private suspend fun call(req: Request, useLongTimeout: Boolean = false): JsonElement =
        suspendCancellableCoroutine { cont ->
            val call = (if (useLongTimeout) longClient else client).newCall(req)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val text = resp.body?.string() ?: ""
                        if (!cont.isActive) return
                        if (!resp.isSuccessful) {
                            cont.resumeWithException(
                                IllegalStateException("HTTP ${resp.code}: ${text.take(300)}"),
                            )
                            return
                        }
                        val el = runCatching { json.parseToJsonElement(text) }
                            .getOrElse { JsonPrimitive(text) }
                        cont.resume(el) {}
                    }
                }
            })
        }

    private fun getReq(path: String): Request = Request.Builder()
        .url(baseUrl + path)
        .header("Authorization", authHeader())
        .build()

    private fun postReq(path: String, bodyJson: String?): Request {
        val body = (bodyJson ?: "").toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", authHeader())
            .post(body)
            .build()
    }

    private fun deleteReq(path: String): Request = Request.Builder()
        .url(baseUrl + path)
        .header("Authorization", authHeader())
        .delete()
        .build()

    // ------------------------------------------------------------- helpers

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.arr(key: String): JsonArray =
        (this[key] as? kotlinx.serialization.json.JsonArray) ?: JsonArray(emptyList())

    private fun JsonObject.obj(key: String): JsonObject =
        (this[key] as? JsonObject) ?: JsonObject(emptyMap())

    /** Ambil array `data`, toleran bila server mengembalikan array telanjang. */
    private fun JsonElement.dataArray(): JsonArray = when (this) {
        is JsonObject -> arr("data")
        is JsonArray -> this
        else -> JsonArray(emptyList())
    }

    // ------------------------------------------------------------- endpoints

    suspend fun health(): ServerHealth = withContext(Dispatchers.IO) {
        // v2: /global/health digantikan /api/info (tak punya `healthy`; 2xx = sehat).
        // PENTING: server v1 / SPA fallback bisa membalas HTML 200 ke path tak dikenal,
        // jadi validasi bentuk JSON v2 (`version`) — kalau bukan, anggap TIDAK sehat.
        val el = call(getReq("/api/info"))
        val o = el as? JsonObject ?: throw IllegalStateException("Bukan server v2 (respons bukan JSON)")
        val version = o.str("version") ?: throw IllegalStateException("Bukan server v2 (tak ada version)")
        ServerHealth(healthy = true, version = version)
    }

    private fun encodeRelPath(path: String): String =
        path.split("/").filter { it.isNotEmpty() }
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }

    /** Daftar isi satu direktori ("" = root working dir server). */
    suspend fun listFiles(path: String = ""): List<FsEntry> = withContext(Dispatchers.IO) {
        val q = if (path.isBlank()) "" else "?path=" + encodeRelPath(path)
        val el = call(getReq("/api/fs/list$q"))
        val data = (el as? JsonObject)?.let { it.dataArray() } ?: return@withContext emptyList()
        data.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val rel = o.str("path")?.let { normalizeFsPath(it) } ?: return@mapNotNull null
            FsEntry(rel, o.str("type") == "directory")
        }
    }

    /** Baca file (teks, dipotong maxChars). */
    suspend fun readFile(path: String, maxChars: Int = 100_000): String = withContext(Dispatchers.IO) {
        val bytes = callBytes(getReq("/api/fs/read/" + encodeRelPath(path)))
        String(bytes, Charsets.UTF_8).take(maxChars)
    }

    /** Baca file mentah (untuk preview gambar). */
    suspend fun readFileBytes(path: String, maxBytes: Int = 8_000_000): ByteArray = withContext(Dispatchers.IO) {
        val bytes = callBytes(getReq("/api/fs/read/" + encodeRelPath(path)))
        if (bytes.size > maxBytes) throw IllegalStateException("File terlalu besar untuk preview")
        bytes
    }

    /** Cari file/direktori rekursif. */
    suspend fun findFiles(query: String, limit: Int = 50): List<FsEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val el = call(getReq("/api/fs/find?query=" + java.net.URLEncoder.encode(query, "UTF-8") + "&limit=$limit"))
        val data = (el as? JsonObject)?.let { it.dataArray() } ?: return@withContext emptyList()
        data.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val rel = o.str("path")?.let { normalizeFsPath(it) } ?: return@mapNotNull null
            FsEntry(rel, o.str("type") == "directory")
        }
    }

    private suspend fun callBytes(req: Request): ByteArray = suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!cont.isActive) return
                    if (!resp.isSuccessful) {
                        cont.resumeWithException(IllegalStateException("HTTP ${resp.code}"))
                        return
                    }
                    cont.resume(resp.body?.bytes() ?: ByteArray(0)) {}
                }
            }
        })
    }

    /**
     * Aliran event server (SSE `/api/event`) di-parse jadi [ServerEvent]
     * semantik. Berhenti saat collector dibatalkan; error koneksi mengakhiri
     * diam-diam (polling 2 dtk tetap jadi jaring pengaman). Parsing longgar:
     * bentuk tak dikenal → [ServerEvent.Other].
     */
    fun serverEvents(): Flow<ServerEvent> = callbackFlow {
        val req = Request.Builder()
            .url(baseUrl + "/api/event")
            .header("Authorization", authHeader())
            .build()
        val call = client.newCall(req)
        try {
            val resp = call.execute()
            if (!resp.isSuccessful) {
                resp.close()
                close()
                return@callbackFlow
            }
            val reader = resp.body?.byteStream()?.bufferedReader()
            if (reader == null) {
                resp.close()
                close()
                return@callbackFlow
            }
            try {
                val data = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isBlank() -> {
                            if (data.isNotEmpty()) {
                                parseServerEvent(data.toString())?.let { trySend(it) }
                            }
                            data.clear()
                        }
                        line.startsWith(":") -> {}
                        line.startsWith("event:") -> {}
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append("\n")
                            data.append(line.removePrefix("data:").trim())
                        }
                    }
                }
            } finally {
                runCatching { resp.close() }
            }
        } catch (e: Exception) {
            // koneksi putus — diam, polling yang ambil alih
        }
        awaitClose { runCatching { call.cancel() } }
    }.flowOn(Dispatchers.IO)

    /** Parse satu payload JSON SSE → [ServerEvent] (V2 `data`, fallback `properties`). */
    private fun parseServerEvent(raw: String): ServerEvent? {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val type = root.str("type") ?: return null
        val payload = (root["data"] as? JsonObject)
            ?: (root["properties"] as? JsonObject)
            ?: JsonObject(emptyMap())
        val sid = payload.str("sessionID")
        return when (type) {
            "server.connected" -> ServerEvent.Connected
            "session.status" -> {
                val st = payload.obj("status")
                ServerEvent.Status(sid, st.str("type") ?: "busy", st.str("message"))
            }
            "session.idle" -> ServerEvent.Status(sid, "idle", null)
            "session.execution.started" -> ServerEvent.Status(sid, "busy", null)
            "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted" ->
                ServerEvent.Status(sid, "idle", null)
            "session.text.delta" ->
                ServerEvent.TextDelta(sid, payload.str("assistantMessageID"), payload.str("delta") ?: "")
            "session.text.ended" ->
                ServerEvent.TextEnded(sid, payload.str("assistantMessageID"), payload.str("text") ?: "")
            "session.reasoning.delta" ->
                ServerEvent.ReasoningDelta(sid, payload.str("assistantMessageID"), payload.str("delta") ?: "")
            "message.part.delta" -> when (payload.str("field")) {
                "text" -> ServerEvent.TextDelta(sid, payload.str("messageID"), payload.str("delta") ?: "")
                "reasoning" -> ServerEvent.ReasoningDelta(sid, payload.str("messageID"), payload.str("delta") ?: "")
                else -> ServerEvent.Other(sid, type)
            }
            "message.part.updated" -> {
                val part = payload.obj("part")
                when (part.str("type")) {
                    "text" -> ServerEvent.TextEnded(sid, part.str("messageID"), part.str("text") ?: "")
                    "tool" -> ServerEvent.Tool(
                        sid,
                        part.str("callID") ?: part.str("id") ?: "",
                        part.str("tool") ?: "",
                        part.obj("state").str("status") ?: "",
                    )
                    else -> ServerEvent.Other(sid, type)
                }
            }
            "session.tool.input.started" -> ServerEvent.Tool(sid, "", payload.str("name") ?: "", "running")
            "session.tool.called" -> ServerEvent.Tool(sid, "", "", "running")
            "session.tool.success" -> ServerEvent.Tool(sid, "", "", "completed")
            "session.tool.failed" -> ServerEvent.Tool(sid, "", "", "error")
            "permission.asked", "permission.v2.asked", "permission.replied", "permission.v2.replied" ->
                ServerEvent.Permission(sid)
            "question.asked", "question.v2.asked", "question.replied", "question.rejected",
            "question.v2.replied", "question.v2.rejected" ->
                ServerEvent.Question(sid)
            "session.error" -> ServerEvent.Error(
                sid,
                payload.obj("error").str("message") ?: payload.str("error") ?: "error",
            )
            else -> ServerEvent.Other(sid, type)
        }
    }

    /** GET mentah untuk debug. */
    suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val el = call(getReq(path))
        el.toString()
    }

    suspend fun listSessions(limit: Int = 50): List<ChatSession> = withContext(Dispatchers.IO) {
        val el = call(getReq("/api/session?limit=$limit&order=desc"))
        el.dataArray().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val model = o.obj("model")
            ChatSession(
                id = id,
                title = o.str("title") ?: "",
                providerID = model.str("providerID"),
                modelID = model.str("id"),
                agentID = o.str("agent"),
            )
        }
    }

    /**
     * Set id sesi yang sedang menjalankan turn di server. Bentuk respons
     * `{data:{<sid>:{type:"running"}}}`, tapi ditoleransi juga array objek.
     */
    suspend fun sessionActive(): Set<String> = withContext(Dispatchers.IO) {
        val el = runCatching { call(getReq("/api/session/active")) }.getOrNull()
            ?: return@withContext emptySet()
        val data = (el as? JsonObject)?.get("data") ?: el
        val out = mutableSetOf<String>()
        when (data) {
            is JsonObject -> data.forEach { (key, value) ->
                val type = (value as? JsonObject)?.str("type")
                if (type == null || type == "running" || type == "busy") out.add(key)
            }
            is JsonArray -> data.forEach { item ->
                val o = item as? JsonObject ?: return@forEach
                val id = o.str("id") ?: o.str("sessionID") ?: return@forEach
                val type = o.str("type") ?: o.str("status")
                if (type == null || type == "running" || type == "busy") out.add(id)
            }
            else -> {}
        }
        out
    }

    suspend fun createSession(): ChatSession = withContext(Dispatchers.IO) {
        val el = call(postReq("/api/session", "{}"))
        val o = (el as? JsonObject)?.obj("data") ?: JsonObject(emptyMap())
        val id = o.str("id") ?: throw IllegalStateException("Server tidak mengembalikan id sesi")
        ChatSession(id = id, title = o.str("title") ?: "")
    }

    suspend fun listMessages(sessionId: String, limit: Int = 200): List<ChatItem> =
        withContext(Dispatchers.IO) {
            // Server membatasi limit<=200. order=asc akan mengembalikan 200
            // pesan TERTUA, sehingga pada sesi panjang balasan terbaru tak pernah
            // tampil ("ga ke sync"). Ambil 200 TERBARU lalu balik untuk tampilan
            // menaik (terlama -> terbaru) yang dipakai UI.
            val el = call(getReq("/api/session/$sessionId/message?limit=$limit&order=desc"))
            el.dataArray().mapNotNull { item -> parseMessage(item) }.asReversed()
        }

    private fun parseMessage(item: JsonElement): ChatItem? {
        val o = item as? JsonObject ?: return null
        // Toleran dua bentuk: { id, type, content:[...] } (wrapper) maupun
        // { info:{...}, parts:[...] } (MessageV2.WithParts).
        val info = o["info"] as? JsonObject
        val meta = info ?: o
        val id = meta.str("id") ?: o.str("id") ?: return null
        val kind = meta.str("type") ?: meta.str("role") ?: o.str("type") ?: o.str("role")
        val parts = o.arr("content").ifEmpty { o.arr("parts") }
            .ifEmpty { meta.arr("content").ifEmpty { meta.arr("parts") } }
        return when (kind) {
            "user" -> {
                val files = o.arr("files").ifEmpty { meta.arr("files") }.size
                val fromParts = parts.mapNotNull { p ->
                    val po = p as? JsonObject ?: return@mapNotNull null
                    if (po.str("type") == "text") po.str("text") else null
                }.joinToString("\n")
                val text = meta.str("text") ?: o.str("text") ?: fromParts
                if (text.isBlank() && files == 0) null
                else ChatItem.User(id, text, files)
            }
            "assistant" -> {
                val texts = mutableListOf<String>()
                val reasonings = mutableListOf<String>()
                val tools = mutableListOf<ToolUse>()
                for (part in parts) {
                    val p = part as? JsonObject ?: continue
                    when (p.str("type")) {
                        "text" -> p.str("text")?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
                        "reasoning" -> p.str("text")?.takeIf { it.isNotBlank() }?.let { reasonings.add(it) }
                        "tool" -> {
                            val st = p.obj("state").str("status") ?: ""
                            tools.add(ToolUse(p.str("id") ?: "", p.str("name") ?: "tool", st))
                        }
                    }
                }
                val finish = meta.str("finish") ?: o.str("finish")
                val err = meta.obj("error").str("message")?.takeIf { it.isNotBlank() }
                    ?: meta.str("error")?.takeIf { it.isNotBlank() }
                    ?: o.obj("error").str("message")?.takeIf { it.isNotBlank() }
                    ?: o.str("error")?.takeIf { it.isNotBlank() }
                val hasBody = texts.isNotEmpty() || reasonings.isNotEmpty() || tools.isNotEmpty()
                if (!hasBody && err == null && finish == null) null
                else ChatItem.Assistant(id, texts, reasonings, tools, finish, err)
            }
            "synthetic", "system" -> {
                val text = meta.str("text") ?: meta.str("description")
                    ?: o.str("text") ?: o.str("description") ?: return null
                if (text.isBlank()) null else ChatItem.Note(id, text)
            }
            else -> null
        }
    }

    /** Kirim pesan (async di server — return saat admit, bukan saat selesai). */
    suspend fun sendPrompt(
        sessionId: String,
        text: String,
        files: List<Attachment> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        // v2: body FLAT `{text, files?}`, additionalProperties:false → buang `mime`.
        val body = buildJsonObject {
            put("text", text)
            if (files.isNotEmpty()) {
                put("files", JsonArray(files.map { a ->
                    buildJsonObject {
                        put("uri", a.uri)
                        put("name", a.name)
                    }
                }))
            }
        }
        val el = call(postReq("/api/session/$sessionId/prompt", body.toString()))
        val o = (el as? JsonObject)?.obj("data") ?: JsonObject(emptyMap())
        o.str("id") ?: ""
    }

    /** Tunggu loop agent idle (long-poll; cancel = Stop). v2: prefix `/api/experimental`. */
    suspend fun waitIdle(sessionId: String) {
        call(postReq("/api/experimental/session/$sessionId/wait", null), useLongTimeout = true)
    }

    /** Hentikan turn yang jalan. */
    suspend fun interrupt(sessionId: String) {
        runCatching { call(postReq("/api/session/$sessionId/interrupt", null)) }
    }

    suspend fun listPermissions(sessionId: String): List<PermissionReq> =
        withContext(Dispatchers.IO) {
            val el = runCatching { call(getReq("/api/session/$sessionId/permission")) }
                .getOrNull() ?: return@withContext emptyList()
            val data = (el as? JsonObject)?.let { it.dataArray() } ?: return@withContext emptyList()
            data.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                val summary = buildString {
                    o.str("action")?.let { append(it) }
                    o.str("tool")?.let { if (isNotEmpty()) append(" ") else Unit; append(it) }
                    val rest = o.entries
                        .filter { (k, v) -> k != "id" && v.toString().length < 200 }
                        .joinToString(" ") { (k, v) -> "$k=${v.toString().trim('\"').take(80)}" }
                    if (rest.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(rest.take(280))
                    }
                }.ifBlank { "Permintaan persetujuan" }
                PermissionReq(id, summary)
            }
        }

    /** reply: "once" | "always" | "reject" (always = allow-all bawaan opencode). */
    suspend fun replyPermission(sessionId: String, requestId: String, reply: String) {
        val body = buildJsonObject { put("reply", reply) }
        call(postReq("/api/session/$sessionId/permission/$requestId/reply", body.toString()))
    }

    // --------------------------------------------------------- form (question tool)

    /**
     * v2 mengganti endpoint `question` dengan `form`. Satu `Form.Info` =
     * `{id, sessionID, title, metadata, fields:[Form.Field]}`; tiap field punya
     * `key`(wajib), `title`, `description`, `hidden`, `type`, `options?`.
     * Dipetakan ke [QuestionRequest]/[QuestionInfo] agar UI lama tetap dipakai.
     */
    private fun parseForm(item: JsonElement): QuestionRequest? {
        val o = item as? JsonObject ?: return null
        val id = o.str("id") ?: return null
        val fields = o.arr("fields")
        val questions = fields.mapNotNull { f ->
            val fo = f as? JsonObject ?: return@mapNotNull null
            if ((fo["hidden"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            val key = fo.str("key") ?: return@mapNotNull null
            val type = fo.str("type") ?: "string"
            val title = fo.str("title") ?: key
            val opts = fo.arr("options")
            QuestionInfo(
                header = title,
                question = fo.str("description") ?: title,
                options = opts.mapNotNull { op ->
                    val oo = op as? JsonObject ?: return@mapNotNull null
                    val value = oo.str("value") ?: return@mapNotNull null
                    QuestionOption(
                        label = oo.str("label") ?: value,
                        description = oo.str("description") ?: "",
                        value = value,
                    )
                },
                multiple = type == "multiselect",
                custom = type in setOf("string", "number", "integer", "external") || opts.isEmpty(),
                key = key,
                type = type,
            )
        }
        if (questions.isEmpty()) return null
        return QuestionRequest(id, questions)
    }

    /** Form/pertanyaan agent yang menunggu jawaban di sesi ini. */
    suspend fun listQuestions(sessionId: String): List<QuestionRequest> = withContext(Dispatchers.IO) {
        val el = runCatching { call(getReq("/api/session/$sessionId/form")) }.getOrNull()
            ?: return@withContext emptyList()
        val data = (el as? JsonObject)?.let { it.dataArray() } ?: return@withContext emptyList()
        data.mapNotNull { parseForm(it) }
    }

    /**
     * Jawab form: `answers[q]` = daftar label terpilih / teks bebas untuk pertanyaan ke-q.
     * Label dipetakan ke `Form.Option.value`; body akhir `{answer:{<key>:<value>}}`.
     */
    suspend fun replyQuestion(sessionId: String, request: QuestionRequest, answers: List<List<String>>) {
        val answer = buildJsonObject {
            request.questions.forEachIndexed { qi, q ->
                if (q.key.isBlank()) return@forEachIndexed
                val labels = answers.getOrElse(qi) { emptyList() }
                val values = labels.map { lbl ->
                    q.options.firstOrNull { it.label == lbl }?.value ?: lbl
                }.filter { it.isNotBlank() }
                when (q.type) {
                    "multiselect" ->
                        if (values.isNotEmpty()) put(q.key, JsonArray(values.map { JsonPrimitive(it) }))
                    "boolean" -> put(q.key, JsonPrimitive(values.firstOrNull()?.toBooleanStrictOrNull() ?: false))
                    "number" -> put(q.key, JsonPrimitive(values.firstOrNull()?.toDoubleOrNull() ?: 0.0))
                    "integer" -> put(q.key, JsonPrimitive(values.firstOrNull()?.toLongOrNull() ?: 0L))
                    else -> values.firstOrNull()?.let { put(q.key, JsonPrimitive(it)) }
                }
            }
        }
        val body = buildJsonObject { put("answer", answer) }
        call(postReq("/api/session/$sessionId/form/${request.id}/reply", body.toString()))
    }

    /** Tolak/tutup form tanpa menjawab (v2: DELETE form). */
    suspend fun rejectQuestion(sessionId: String, requestId: String) {
        runCatching { call(deleteReq("/api/session/$sessionId/form/$requestId")) }
    }

    /** Ganti model sesi (server-side; pengganti pin model Arka). */
    suspend fun switchModel(sessionId: String, providerID: String, modelID: String) {
        val body = buildJsonObject {
            put("model", buildJsonObject {
                put("id", modelID)
                put("providerID", providerID)
            })
        }
        call(postReq("/api/session/$sessionId/model", body.toString()))
    }

    /** Semua model flat dari server (sumber kebenaran untuk picker). */
    suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val el = runCatching { call(getReq("/api/model")) }.getOrNull()
            ?: return@withContext emptyList()
        val arr = (el as? JsonObject)?.let { it.dataArray() }
            ?: (el as? JsonArray) ?: return@withContext emptyList()
        arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val mid = o.str("id") ?: return@mapNotNull null
            val pid = o.str("providerID") ?: return@mapNotNull null
            val costs = o.arr("cost")
            val free = costs.isNotEmpty() && costs.all { c ->
                val co = c as? JsonObject ?: return@all false
                val inp = (co["input"] as? JsonPrimitive)?.doubleOrNull ?: Double.NaN
                val out = (co["output"] as? JsonPrimitive)?.doubleOrNull ?: Double.NaN
                !inp.isNaN() && !out.isNaN() && inp == 0.0 && out == 0.0
            }
            val apiType = o.obj("api").str("type")
            ModelInfo(mid, o.str("name") ?: mid, pid, free, apiType, o.str("status"))
        }
    }

    suspend fun listProviders(): List<ProviderInfo> = withContext(Dispatchers.IO) {
        val el = runCatching { call(getReq("/api/provider")) }.getOrNull()
            ?: return@withContext emptyList()
        val arr = (el as? JsonObject)?.let { it.dataArray() }
            ?: (el as? JsonArray) ?: return@withContext emptyList()
        arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: o.str("name") ?: return@mapNotNull null
            ProviderInfo(id, o.str("name") ?: id, emptyList())
        }
    }

    // ------------------------------------------------------- agents/commands/skills

    /** Agent yang terdaftar (untuk pemilih agent per sesi). */
    suspend fun listAgents(): List<AgentInfo> = withContext(Dispatchers.IO) {
        val el = runCatching { call(getReq("/api/agent")) }.getOrNull()
            ?: return@withContext emptyList()
        val arr = (el as? JsonObject)?.let { it.dataArray() }
            ?: (el as? JsonArray) ?: return@withContext emptyList()
        arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            AgentInfo(
                id = id,
                description = o.str("description") ?: "",
                mode = o.str("mode") ?: "all",
                color = o.str("color"),
                hidden = (o["hidden"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
    }

    /** Ganti agent sesi (berlaku untuk turn berikutnya). */
    suspend fun switchAgent(sessionId: String, agentId: String) {
        val body = buildJsonObject { put("agent", agentId) }
        call(postReq("/api/session/$sessionId/agent", body.toString()))
    }

    /** Perintah (slash command) yang terdaftar. */
    suspend fun listCommands(): List<CommandInfo> = withContext(Dispatchers.IO) {
        val el = runCatching { call(getReq("/api/command")) }.getOrNull()
            ?: return@withContext emptyList()
        val arr = (el as? JsonObject)?.let { it.dataArray() }
            ?: (el as? JsonArray) ?: return@withContext emptyList()
        arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            CommandInfo(
                name = name,
                description = o.str("description") ?: "",
                agent = o.str("agent"),
                template = o.str("template"),
                model = o.str("model"),
                subtask = (o["subtask"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
    }

    /** Skill yang terdaftar di server. */
    suspend fun listSkills(): List<SkillInfo> = withContext(Dispatchers.IO) {
        val el = runCatching { call(getReq("/api/skill")) }.getOrNull()
            ?: return@withContext emptyList()
        val arr = (el as? JsonObject)?.let { it.dataArray() }
            ?: (el as? JsonArray) ?: return@withContext emptyList()
        arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            SkillInfo(
                name = name,
                description = o.str("description") ?: "",
                location = o.str("location") ?: "",
                slash = (o["slash"] as? JsonPrimitive)?.booleanOrNull == true,
                content = o.str("content"),
            )
        }
    }

    // ------------------------------------------------------- compact/revert

    /** Ringkas konteks percakapan (server-side compaction). */
    suspend fun compactSession(sessionId: String) {
        call(postReq("/api/session/$sessionId/compact", null))
    }

    /** Stage revert ke sebuah pesan; mengembalikan berkas yang berubah. */
    suspend fun stageRevert(sessionId: String, messageId: String, files: Boolean = true): RevertState =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("messageID", messageId)
                put("files", files)
            }
            val el = call(postReq("/api/session/$sessionId/revert/stage", body.toString()))
            val o = (el as? JsonObject)?.obj("data") ?: JsonObject(emptyMap())
            val diffs = o.arr("files").mapNotNull { item ->
                val d = item as? JsonObject ?: return@mapNotNull null
                val path = d.str("path") ?: return@mapNotNull null
                FileDiffEntry(
                    path = path,
                    status = d.str("status") ?: "",
                    additions = (d["additions"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                    deletions = (d["deletions"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                    patch = d.str("patch") ?: "",
                )
            }
            RevertState(o.str("messageID") ?: messageId, diffs)
        }

    /** Buang revert yang distage (tidak mengubah berkas). v2: DELETE /revert. */
    suspend fun clearRevert(sessionId: String) {
        call(deleteReq("/api/session/$sessionId/revert"))
    }

    /** Terapkan revert yang distage (kembalikan berkas ke snapshot). */
    suspend fun commitRevert(sessionId: String) {
        call(postReq("/api/session/$sessionId/revert/commit", null))
    }

    // ---------------------------------------------------------- integrations

    private fun parseIntegration(item: JsonElement): IntegrationInfo? {
        val o = item as? JsonObject ?: return null
        val id = o.str("id") ?: return null
        val methods = o.arr("methods").mapNotNull { m ->
            val mo = m as? JsonObject ?: return@mapNotNull null
            val mtype = mo.str("type") ?: return@mapNotNull null
            // v2: metode oauth/key/external membawa `form` (Form.Fields).
            // Fallback (legacy): `prompts`. Keduanya punya {key,type,options,when}.
            val rawFields = mo.arr("form").ifEmpty { mo.arr("prompts") }
            val prompts = rawFields.mapNotNull { p ->
                val po = p as? JsonObject ?: return@mapNotNull null
                val pkey = po.str("key") ?: return@mapNotNull null
                val opts = po.arr("options")
                val wh = when (val w = po["when"]) {
                    is JsonArray -> w.firstOrNull() as? JsonObject
                    is JsonObject -> w
                    else -> null
                }
                IntegrationPrompt(
                    type = if (opts.isNotEmpty()) "select" else (po.str("type") ?: "text"),
                    key = pkey,
                    message = po.str("message") ?: po.str("description") ?: po.str("title") ?: pkey,
                    placeholder = po.str("placeholder"),
                    options = opts.mapNotNull { op ->
                        val oo = op as? JsonObject ?: return@mapNotNull null
                        val v = oo.str("value") ?: return@mapNotNull null
                        IntegrationOption(oo.str("label") ?: v, v, oo.str("hint"))
                    },
                    whenKey = wh?.str("key"),
                    whenOp = wh?.str("op"),
                    whenValue = wh?.str("value"),
                    default = po["default"]?.let { d ->
                        (d as? JsonPrimitive)?.contentOrNull
                            ?: (d as? JsonArray)?.joinToString(",") { (it as? JsonPrimitive)?.contentOrNull ?: "" }
                    },
                    required = (po["required"] as? JsonPrimitive)?.contentOrNull == "true",
                )
            }
            IntegrationMethod(
                id = mo.str("id"),
                type = mtype,
                label = mo.str("label") ?: mtype,
                prompts = prompts,
                envNames = mo.arr("names").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            )
        }
        val connections = o.arr("connections").mapNotNull { c ->
            val co = c as? JsonObject ?: return@mapNotNull null
            IntegrationConnection(
                type = co.str("type") ?: "",
                id = co.str("id"),
                label = co.str("label"),
                envName = co.str("name"),
                method = co.str("method"),
                // `status.status == "needs_auth"` = token kedaluwarsa/direvoke;
                // UI menandai supaya user tahu harus OAuth ulang, bukan cuma "connected".
                needsAuth = (co["status"] as? JsonObject)?.str("status") == "needs_auth",
            )
        }
        return IntegrationInfo(id, o.str("name") ?: id, methods, connections)
    }

    /** Integrasi & metode autentikasinya. */
    suspend fun listIntegrations(): List<IntegrationInfo> = withContext(Dispatchers.IO) {
        val el = runCatching { call(getReq("/api/integration")) }.getOrNull()
            ?: return@withContext emptyList()
        val arr = (el as? JsonObject)?.let { it.dataArray() } ?: (el as? JsonArray)
            ?: return@withContext emptyList()
        arr.mapNotNull { parseIntegration(it) }
    }

    suspend fun getIntegration(id: String): IntegrationInfo? = withContext(Dispatchers.IO) {
        val el = runCatching {
            call(getReq("/api/integration/" + java.net.URLEncoder.encode(id, "UTF-8")))
        }.getOrNull() ?: return@withContext null
        val o = (el as? JsonObject)?.obj("data") ?: el as? JsonObject ?: return@withContext null
        parseIntegration(o)
    }

    /** Hubungkan integrasi memakai API key. */
    suspend fun connectIntegrationKey(integrationId: String, key: String, label: String? = null) {
        val body = buildJsonObject {
            put("key", key)
            if (label != null) put("label", label)
        }
        call(
            postReq(
                "/api/integration/" + java.net.URLEncoder.encode(integrationId, "UTF-8") + "/connect/key",
                body.toString(),
            ),
        )
    }

    /** Mulai OAuth; balikkan URL otorisasi + instruksi. */
    suspend fun beginOAuth(
        integrationId: String,
        methodID: String,
        inputs: Map<String, String>,
        label: String? = null,
    ): OAuthAttempt = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("methodID", methodID)
            // v2: field form/inputs bernama `answer` (Form.Answer), bukan `inputs`.
            put("answer", buildJsonObject { inputs.forEach { (k, v) -> put(k, v) } })
            if (label != null) put("label", label)
        }
        val el = call(
            postReq(
                "/api/integration/" + java.net.URLEncoder.encode(integrationId, "UTF-8") + "/connect/oauth",
                body.toString(),
            ),
        )
        val o = (el as? JsonObject)?.obj("data") ?: (el as? JsonObject) ?: JsonObject(emptyMap())
        OAuthAttempt(
            attemptID = o.str("attemptID") ?: "",
            url = o.str("url") ?: "",
            instructions = o.str("instructions") ?: "",
            mode = o.str("mode") ?: "auto",
        )
    }

    private fun oauthPath(integrationId: String, attemptID: String): String {
        val i = java.net.URLEncoder.encode(integrationId, "UTF-8")
        val a = java.net.URLEncoder.encode(attemptID, "UTF-8")
        return "/api/integration/$i/connect/oauth/$a"
    }

    /**
     * Status attempt OAuth. `null` = gagal jaringan sementara (server tak
     * terjangkau) — pemanggil sebaiknya lanjut polling, bukan menyimpulkan gagal.
     * v2: attempt kini bersarang di bawah integrasi (`connect/oauth/{attemptID}`).
     */
    suspend fun oauthStatus(integrationId: String, attemptID: String): OAuthStatus? = withContext(Dispatchers.IO) {
        val el = runCatching { call(getReq(oauthPath(integrationId, attemptID))) }.getOrNull()
            ?: return@withContext null
        val o = (el as? JsonObject)?.obj("data") ?: (el as? JsonObject) ?: JsonObject(emptyMap())
        OAuthStatus(o.str("status") ?: "pending", o.str("message"))
    }

    suspend fun completeOAuth(integrationId: String, attemptID: String, code: String?) {
        val body = buildJsonObject { if (!code.isNullOrBlank()) put("code", code) }
        call(postReq(oauthPath(integrationId, attemptID) + "/complete", body.toString()))
    }

    suspend fun cancelOAuth(integrationId: String, attemptID: String) {
        runCatching { call(deleteReq(oauthPath(integrationId, attemptID))) }
    }

    /**
     * Cabut credential tersimpan (putuskan OAuth / hapus API key integrasi).
     *
     * Berbeda dari [cancelOAuth] yang membatalkan attempt yang sedang berjalan;
     * ini menghapus kredensial yang sudah jadi, dari `connections[].id`.
     */
    suspend fun removeCredential(credentialID: String) {
        call(deleteReq("/api/credential/" + java.net.URLEncoder.encode(credentialID, "UTF-8")))
    }

    /**
     * Hubungkan integrasi bertipe `external` (Google ADC, AWS profile).
     *
     * Berbeda dengan [beginOAuth]: tanpa attempt dan tanpa browser — server
     * membaca kredensial dari `gcloud auth` / konfigurasi AWS di dalam proot.
     * Balas 204 kalau tersimpan, 400 kalau field wajib kosong.
     */
    suspend fun connectIntegrationExternal(
        integrationId: String,
        methodID: String,
        inputs: Map<String, String>,
    ) {
        val body = buildJsonObject {
            put("methodID", methodID)
            put("answer", buildJsonObject { inputs.forEach { (k, v) -> put(k, v) } })
        }
        call(
            postReq(
                "/api/integration/" + java.net.URLEncoder.encode(integrationId, "UTF-8") + "/connect/external",
                body.toString(),
            ),
        )
    }
}
