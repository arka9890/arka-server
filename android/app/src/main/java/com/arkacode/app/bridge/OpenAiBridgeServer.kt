package com.arkacode.app.bridge

import com.arkacode.app.core.Attachment
import com.arkacode.app.net.ChatItem
import com.arkacode.app.net.OpenCodeClient
import com.arkacode.app.net.ServerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.UUID

/**
 * Helper pemformatan dan parsing format OpenAI <-> OpenCode.
 */
object OpenAiFormatHelper {

    fun parseMessageContent(contentElement: JsonElement?): Pair<String, List<Attachment>> {
        when (contentElement) {
            is JsonPrimitive -> return Pair(contentElement.content, emptyList())
            is JsonArray -> {
                val textBuilder = StringBuilder()
                val attachments = mutableListOf<Attachment>()
                var imgIdx = 1
                for (part in contentElement) {
                    if (part !is JsonObject) continue
                    val type = (part["type"] as? JsonPrimitive)?.contentOrNull
                    if (type == "text") {
                        val t = (part["text"] as? JsonPrimitive)?.contentOrNull ?: ""
                        if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                        textBuilder.append(t)
                    } else if (type == "image_url") {
                        val imgObj = part["image_url"] as? JsonObject
                        val url = (imgObj?.get("url") as? JsonPrimitive)?.contentOrNull ?: ""
                        if (url.startsWith("data:")) {
                            val mime = url.substringAfter("data:").substringBefore(";", "image/jpeg")
                            attachments.add(Attachment(name = "image-$imgIdx.jpg", uri = url, mime = mime))
                            imgIdx++
                        }
                    }
                }
                return Pair(textBuilder.toString(), attachments)
            }
            else -> return Pair("", emptyList())
        }
    }

    /**
     * Mengubah riwayat percakapan messages OpenAI menjadi prompt terstruktur untuk OpenCode.
     */
    fun buildPrompt(messages: JsonArray): Pair<String, List<Attachment>> {
        val attachments = mutableListOf<Attachment>()
        if (messages.isEmpty()) return Pair("", emptyList())

        if (messages.size == 1) {
            val obj = messages[0] as? JsonObject
            val (text, atts) = parseMessageContent(obj?.get("content"))
            return Pair(text, atts)
        }

        val systemParts = mutableListOf<String>()
        val conversationParts = mutableListOf<String>()
        var lastUserText = ""

        for (i in 0 until messages.size) {
            val msg = messages[i] as? JsonObject ?: continue
            val role = (msg["role"] as? JsonPrimitive)?.contentOrNull ?: "user"
            val (text, atts) = parseMessageContent(msg["content"])
            attachments.addAll(atts)

            if (role == "system") {
                if (text.isNotBlank()) systemParts.add(text)
            } else if (i == messages.size - 1 && role == "user") {
                lastUserText = text
            } else {
                val sender = when (role) {
                    "assistant" -> "Assistant"
                    "user" -> "User"
                    else -> role.replaceFirstChar { it.uppercase() }
                }
                if (text.isNotBlank()) conversationParts.add("$sender: $text")
            }
        }

        val sb = StringBuilder()
        if (systemParts.isNotEmpty()) {
            sb.append("[System Instructions]\n")
            sb.append(systemParts.joinToString("\n\n"))
            sb.append("\n\n")
        }
        if (conversationParts.isNotEmpty()) {
            sb.append("[Conversation History]\n")
            sb.append(conversationParts.joinToString("\n"))
            sb.append("\n\n")
        }
        if (lastUserText.isNotEmpty()) {
            sb.append(lastUserText)
        }

        return Pair(sb.toString().trim(), attachments)
    }

    /**
     * Memisahkan parameter model OpenAI.
     * "anthropic/claude-3-7-sonnet" -> Pair("anthropic", "claude-3-7-sonnet")
     * "gpt-4o" -> Pair(null, "gpt-4o")
     */
    fun parseModel(modelParam: String): Pair<String?, String> {
        val trimmed = modelParam.trim()
        return if (trimmed.contains("/")) {
            Pair(trimmed.substringBefore("/"), trimmed.substringAfter("/"))
        } else {
            Pair(null, trimmed)
        }
    }

    fun buildChunkJson(
        id: String,
        model: String,
        created: Long,
        content: String? = null,
        reasoning: String? = null,
    ): String = buildJsonObject {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", created)
        put("model", model)
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                put("delta", buildJsonObject {
                    if (content != null) put("content", content)
                    if (reasoning != null) put("reasoning_content", reasoning)
                })
                put("finish_reason", kotlinx.serialization.json.JsonNull)
            })
        })
    }.toString()

    fun buildStopChunkJson(id: String, model: String, created: Long): String = buildJsonObject {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", created)
        put("model", model)
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                put("delta", buildJsonObject {})
                put("finish_reason", "stop")
            })
        })
    }.toString()
}

/**
 * OpenAI-compatible HTTP Bridge Server.
 *
 * Menerima request standar OpenAI (/v1/chat/completions, /v1/models) dan
 * meneruskannya ke engine opencode lokal via OpenCodeClient.
 */
class OpenAiBridgeServer(
    val bridgePort: Int,
    val targetServerPort: Int,
    private val serverPassword: String,
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val openCodeClient = OpenCodeClient(port = targetServerPort, password = serverPassword)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var running = false

    fun isAlive(): Boolean = running && serverSocket?.isClosed == false

    fun start() {
        if (running) return
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("0.0.0.0", bridgePort))
            serverSocket = server
            running = true

            scope.launch {
                while (isActive && running && !server.isClosed) {
                    try {
                        val clientSocket = server.accept()
                        launch {
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (server.isClosed || !running) break
                    }
                }
            }
        } catch (e: Exception) {
            running = false
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 120_000
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val path = rawPath.substringBefore("?").trimEnd('/')

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase()
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }

            if (method == "OPTIONS") {
                sendCorsPreflight(output)
                return
            }

            if (serverPassword.isNotBlank()) {
                val authHeader = headers["authorization"] ?: ""
                val apiKeyHeader = headers["x-api-key"] ?: ""
                val token = when {
                    authHeader.startsWith("Bearer ", ignoreCase = true) ->
                        authHeader.substring(7).trim()
                    authHeader.startsWith("Basic ", ignoreCase = true) -> {
                        val decoded = runCatching {
                            String(Base64.getDecoder().decode(authHeader.substring(6).trim()))
                        }.getOrNull() ?: ""
                        decoded.substringAfter(":", decoded)
                    }
                    apiKeyHeader.isNotBlank() -> apiKeyHeader.trim()
                    else -> ""
                }
                if (token != serverPassword) {
                    sendJsonError(
                        output,
                        401,
                        "Unauthorized: Bearer token / API key tidak cocok dengan password server.",
                        "invalid_api_key",
                    )
                    return
                }
            }

            when {
                method == "GET" && (path == "" || path == "/" || path == "/health" || path == "/v1/health") -> {
                    val body = """{"status":"ok","bridge":"arka-server-openai-bridge","version":"1.0.0"}"""
                    sendJsonResponse(output, 200, body)
                }

                method == "GET" && (path == "/v1/models" || path == "/models") -> {
                    handleListModels(output)
                }

                method == "POST" && (path == "/v1/chat/completions" || path == "/chat/completions") -> {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyBytes = if (contentLength > 0) {
                        readExactBytes(input, contentLength)
                    } else {
                        ByteArray(0)
                    }
                    val bodyText = String(bodyBytes, Charsets.UTF_8)
                    handleChatCompletions(output, bodyText)
                }

                else -> {
                    sendJsonError(output, 404, "Endpoint '$rawPath' tidak ditemukan.", "not_found")
                }
            }
        }
    }

    private suspend fun handleListModels(output: BufferedOutputStream) {
        try {
            val models = runCatching { openCodeClient.listModels() }.getOrDefault(emptyList())
            val dataArray = buildJsonArray {
                models.forEach { m ->
                    val fullId = "${m.providerID}/${m.id}"
                    add(buildJsonObject {
                        put("id", fullId)
                        put("object", "model")
                        put("created", 1700000000)
                        put("owned_by", m.providerID)
                    })
                    if (m.id != fullId) {
                        add(buildJsonObject {
                            put("id", m.id)
                            put("object", "model")
                            put("created", 1700000000)
                            put("owned_by", m.providerID)
                        })
                    }
                }
            }
            val responseJson = buildJsonObject {
                put("object", "list")
                put("data", dataArray)
            }.toString()
            sendJsonResponse(output, 200, responseJson)
        } catch (e: Exception) {
            sendJsonError(output, 500, "Gagal mengambil daftar model: ${e.message}", "server_error")
        }
    }

    private suspend fun handleChatCompletions(output: BufferedOutputStream, bodyText: String) {
        val root = try {
            json.parseToJsonElement(bodyText) as? JsonObject
                ?: throw IllegalArgumentException("Body bukan JSON object")
        } catch (e: Exception) {
            sendJsonError(output, 400, "JSON request tidak valid: ${e.message}", "invalid_request_error")
            return
        }

        val modelParam = (root["model"] as? JsonPrimitive)?.contentOrNull ?: ""
        val messagesArray = (root["messages"] as? JsonArray) ?: JsonArray(emptyList())
        val stream = (root["stream"] as? JsonPrimitive)?.booleanOrNull ?: false

        if (messagesArray.isEmpty()) {
            sendJsonError(output, 400, "Array 'messages' tidak boleh kosong", "invalid_request_error")
            return
        }

        val (promptText, attachments) = OpenAiFormatHelper.buildPrompt(messagesArray)
        if (promptText.isBlank() && attachments.isEmpty()) {
            sendJsonError(output, 400, "Pesan tidak memuat teks atau lampiran yang valid", "invalid_request_error")
            return
        }

        val session = try {
            openCodeClient.createSession()
        } catch (e: Exception) {
            sendJsonError(output, 502, "Gagal membuat sesi di engine opencode: ${e.message}", "bad_gateway")
            return
        }

        if (modelParam.isNotBlank()) {
            resolveAndSwitchModel(session.id, modelParam)
        }

        val completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val created = System.currentTimeMillis() / 1000

        if (stream) {
            handleStreamResponse(output, session.id, completionId, modelParam, promptText, attachments, created)
        } else {
            handleNonStreamResponse(output, session.id, completionId, modelParam, promptText, attachments, created)
        }
    }

    private suspend fun resolveAndSwitchModel(sessionId: String, modelParam: String) {
        try {
            val (providerId, modelId) = OpenAiFormatHelper.parseModel(modelParam)
            if (providerId != null) {
                openCodeClient.switchModel(sessionId, providerId, modelId)
            } else {
                val models = runCatching { openCodeClient.listModels() }.getOrNull() ?: emptyList()
                val match = models.firstOrNull { it.id.equals(modelId, ignoreCase = true) }
                    ?: models.firstOrNull { it.name.equals(modelId, ignoreCase = true) }
                if (match != null) {
                    openCodeClient.switchModel(sessionId, match.providerID, match.id)
                }
            }
        } catch (e: Exception) {
            // switch model best-effort
        }
    }

    private suspend fun handleNonStreamResponse(
        output: BufferedOutputStream,
        sessionId: String,
        completionId: String,
        modelParam: String,
        promptText: String,
        attachments: List<Attachment>,
        created: Long,
    ) {
        try {
            openCodeClient.sendPrompt(sessionId, promptText, attachments)
            withTimeoutOrNull(180_000) {
                openCodeClient.waitIdle(sessionId)
            }
            val messages = openCodeClient.listMessages(sessionId)
            val assistant = messages.filterIsInstance<ChatItem.Assistant>().lastOrNull()
            val text = assistant?.texts?.joinToString("\n") ?: ""

            val responseJson = buildJsonObject {
                put("id", completionId)
                put("object", "chat.completion")
                put("created", created)
                put("model", modelParam.ifBlank { "opencode-default" })
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("message", buildJsonObject {
                            put("role", "assistant")
                            put("content", text)
                        })
                        put("finish_reason", "stop")
                    })
                })
                put("usage", buildJsonObject {
                    put("prompt_tokens", 0)
                    put("completion_tokens", 0)
                    put("total_tokens", 0)
                })
            }.toString()

            sendJsonResponse(output, 200, responseJson)
        } catch (e: Exception) {
            sendJsonError(output, 500, "Error saat eksekusi prompt: ${e.message}", "server_error")
        }
    }

    private suspend fun handleStreamResponse(
        output: BufferedOutputStream,
        sessionId: String,
        completionId: String,
        modelParam: String,
        promptText: String,
        attachments: List<Attachment>,
        created: Long,
    ) {
        val modelName = modelParam.ifBlank { "opencode-default" }

        val headerStr = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n"
        output.write(headerStr.toByteArray(Charsets.UTF_8))
        output.flush()

        val textEmitted = StringBuilder()
        val collectorJob = scope.launch {
            try {
                openCodeClient.serverEvents().collect { ev ->
                    if (ev.sessionID == sessionId) {
                        when (ev) {
                        is ServerEvent.TextDelta -> {
                            if (ev.delta.isNotEmpty()) {
                                textEmitted.append(ev.delta)
                                val chunk = OpenAiFormatHelper.buildChunkJson(completionId, modelName, created, content = ev.delta)
                                sendSseChunk(output, chunk)
                            }
                        }
                        is ServerEvent.ReasoningDelta -> {
                            if (ev.delta.isNotEmpty()) {
                                val chunk = OpenAiFormatHelper.buildChunkJson(completionId, modelName, created, reasoning = ev.delta)
                                sendSseChunk(output, chunk)
                            }
                        }
                        else -> Unit
                    }
                }
            }
        } catch (e: Exception) {
            // collector dibatalkan
        }
    }

    try {
        openCodeClient.sendPrompt(sessionId, promptText, attachments)
        withTimeoutOrNull(180_000) {
            openCodeClient.waitIdle(sessionId)
        }
    } finally {
        collectorJob.cancel()
    }

    if (textEmitted.isEmpty()) {
        val msgs = runCatching { openCodeClient.listMessages(sessionId) }.getOrNull()
        val assistant = msgs?.filterIsInstance<ChatItem.Assistant>()?.lastOrNull()
        val text = assistant?.texts?.joinToString("\n") ?: ""
        if (text.isNotEmpty()) {
            val chunk = OpenAiFormatHelper.buildChunkJson(completionId, modelName, created, content = text)
            sendSseChunk(output, chunk)
        }
    }

    val stopChunk = OpenAiFormatHelper.buildStopChunkJson(completionId, modelName, created)
    sendSseChunk(output, stopChunk)

    output.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
    output.flush()
}

    private fun sendSseChunk(output: BufferedOutputStream, chunkJson: String) {
        runCatching {
            output.write("data: $chunkJson\n\n".toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private fun sendCorsPreflight(output: BufferedOutputStream) {
        val headers = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Authorization, Content-Type, Accept, Origin, X-Requested-With, x-api-key\r\n" +
                "Access-Control-Max-Age: 86400\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun sendJsonResponse(output: BufferedOutputStream, status: Int, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val statusText = when (status) {
            200 -> "OK"
            201 -> "Created"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            else -> "Response"
        }
        val headers = "HTTP/1.1 $status $statusText\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }

    private fun sendJsonError(output: BufferedOutputStream, status: Int, message: String, code: String) {
        val body = buildJsonObject {
            put("error", buildJsonObject {
                put("message", message)
                put("type", if (status >= 500) "server_error" else "invalid_request_error")
                put("code", code)
            })
        }.toString()
        sendJsonResponse(output, status, body)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (baos.size() == 0) return null
                break
            }
            if (b == '\n'.code) {
                break
            }
            if (b != '\r'.code) {
                baos.write(b)
            }
        }
        return baos.toString("UTF-8")
    }

    private fun readExactBytes(input: BufferedInputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = input.read(buf, totalRead, length - totalRead)
            if (read < 0) break
            totalRead += read
        }
        return if (totalRead == length) buf else buf.copyOf(totalRead)
    }
}
