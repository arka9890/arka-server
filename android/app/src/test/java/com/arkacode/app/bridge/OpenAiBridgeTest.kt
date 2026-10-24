package com.arkacode.app.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiBridgeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parseModel_handlesProviderPrefix() {
        val (provider, model) = OpenAiFormatHelper.parseModel("anthropic/claude-3-7-sonnet")
        assertEquals("anthropic", provider)
        assertEquals("claude-3-7-sonnet", model)
    }

    @Test
    fun parseModel_handlesBareModel() {
        val (provider, model) = OpenAiFormatHelper.parseModel("gpt-4o")
        assertNull(provider)
        assertEquals("gpt-4o", model)
    }

    @Test
    fun parseMessageContent_stringText() {
        val (text, attachments) = OpenAiFormatHelper.parseMessageContent(JsonPrimitive("Halo dunia!"))
        assertEquals("Halo dunia!", text)
        assertTrue(attachments.isEmpty())
    }

    @Test
    fun parseMessageContent_multimodalParts() {
        val parts = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "Apa yang ada di gambar ini?")
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject {
                    put("url", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
                })
            })
        }
        val (text, attachments) = OpenAiFormatHelper.parseMessageContent(parts)
        assertEquals("Apa yang ada di gambar ini?", text)
        assertEquals(1, attachments.size)
        assertEquals("image/png", attachments[0].mime)
        assertTrue(attachments[0].uri.startsWith("data:image/png;base64,"))
    }

    @Test
    fun buildPrompt_singleUserMessage() {
        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", "Buatkan fungsi prima")
            })
        }
        val (prompt, atts) = OpenAiFormatHelper.buildPrompt(messages)
        assertEquals("Buatkan fungsi prima", prompt)
        assertTrue(atts.isEmpty())
    }

    @Test
    fun buildPrompt_multiTurnWithSystemAndHistory() {
        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", "Anda adalah asisten coding ramah.")
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", "Halo!")
            })
            add(buildJsonObject {
                put("role", "assistant")
                put("content", "Halo! Ada yang bisa saya bantu?")
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", "Tuliskan hello world di Kotlin")
            })
        }
        val (prompt, atts) = OpenAiFormatHelper.buildPrompt(messages)
        assertTrue(prompt.contains("[System Instructions]"))
        assertTrue(prompt.contains("Anda adalah asisten coding ramah."))
        assertTrue(prompt.contains("[Conversation History]"))
        assertTrue(prompt.contains("User: Halo!"))
        assertTrue(prompt.contains("Assistant: Halo! Ada yang bisa saya bantu?"))
        assertTrue(prompt.contains("Tuliskan hello world di Kotlin"))
        assertTrue(atts.isEmpty())
    }

    @Test
    fun chunkJson_matchesOpenAiSpec() {
        val chunkStr = OpenAiFormatHelper.buildChunkJson(
            id = "chatcmpl-test1234",
            model = "gpt-4o",
            created = 1700000000L,
            content = "Halo",
        )
        val obj = json.parseToJsonElement(chunkStr).jsonObject
        assertEquals("chatcmpl-test1234", obj["id"]?.jsonPrimitive?.content)
        assertEquals("chat.completion.chunk", obj["object"]?.jsonPrimitive?.content)
        assertEquals(1700000000L, obj["created"]?.jsonPrimitive?.content?.toLong())
        assertEquals("gpt-4o", obj["model"]?.jsonPrimitive?.content)

        val choices = obj["choices"]?.jsonArray
        assertNotNull(choices)
        assertEquals(1, choices?.size)
        val choice0 = choices?.get(0)?.jsonObject
        assertEquals(0, choice0?.get("index")?.jsonPrimitive?.content?.toInt())
        val delta = choice0?.get("delta")?.jsonObject
        assertEquals("Halo", delta?.get("content")?.jsonPrimitive?.content)
    }

    @Test
    fun stopChunkJson_matchesOpenAiSpec() {
        val chunkStr = OpenAiFormatHelper.buildStopChunkJson(
            id = "chatcmpl-test1234",
            model = "gpt-4o",
            created = 1700000000L,
        )
        val obj = json.parseToJsonElement(chunkStr).jsonObject
        val choice0 = obj["choices"]?.jsonArray?.get(0)?.jsonObject
        assertEquals("stop", choice0?.get("finish_reason")?.jsonPrimitive?.content)
    }
}
