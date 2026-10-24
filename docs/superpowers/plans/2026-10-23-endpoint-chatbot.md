# Rencana: Endpoint Chatbot (OpenAI-compatible)

Spec: `docs/superpowers/specs/2026-10-23-endpoint-chatbot-design.md`

Tujuan: satu bridge HTTP di dalam APK (port **4098**) yang menerjemahkan
`/v1/models` + `/v1/chat/completions` ke API internal opencode (port 4097).
Mode agent/web UI tidak berubah.

Batasan lingkungan: tidak ada JDK/Android SDK di sandbox → kompilasi & uji
instrumentasi dilakukan di build pengguna. Unit test JVM untuk logika murni
tetap ditulis dan bisa dijalankan lewat `./gradlew testDebugUnitTest`.

---

## Task 1 — Dependency NanoHTTPD

**Files:** `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`

1. Tambah `nanohttpd = "2.3.1"` di `[versions]`.
2. Tambah `nanohttpd = { group = "org.nanohttpd", name = "nanohttpd", version.ref = "nanohttpd" }`
   di `[libraries]`.
3. Tambah `implementation(libs.nanohttpd)` di blok `dependencies`.

Verifikasi: `./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep nanohttpd`.

---

## Task 2 — Logika murni + unit test

**File baru:** `android/app/src/main/java/com/arkacode/app/net/ChatbotWire.kt`

Objek tanpa dependensi Android, supaya bisa diuji di JVM:

```kotlin
object ChatbotWire {
    /** Body OpenAI -> prompt teks untuk opencode. Content boleh string atau array part. */
    fun prompt(json: JsonObject): String?          // null = messages kosong/tidak valid

    /** "providerID/modelID" -> Pair(providerID, modelID); null bila tanpa "/". */
    fun splitModel(id: String): Pair<String, String>?

    /** Potongan teks dari content (string | [{type:"text",text:...}]). */
    fun contentText(content: JsonElement?): String
}
```

Prompt = tiap pesan `ROLE: teks`, dipisah satu baris kosong. Role di-uppercase.
Pesan tanpa teks dilewati. Hasil kosong → `null`.

**File baru:** `android/app/src/test/java/com/arkacode/app/net/ChatbotWireTest.kt`

Test wajib:
- string content → prompt `USER: halo`.
- array part (text + image_url) → hanya teks yang diambil.
- system + user + assistant → urutan & pemisah benar.
- messages kosong → null.
- `splitModel("openai/gpt-4o")` → `openai` / `gpt-4o`; `splitModel("gpt-4o")` → null;
  `splitModel("custom/qwen/7b")` → `custom` / `qwen/7b` (split **pertama**).

Verifikasi: `./gradlew :app:testDebugUnitTest --tests com.arkacode.app.net.ChatbotWireTest`.

---

## Task 3 — `OpenCodeClient.deleteSession`

**File:** `android/app/src/main/java/com/arkacode/app/net/OpenCodeClient.kt`

Tambah dekat `createSession`:

```kotlin
/** Hapus sesi (dipakai bridge agar sesi chatbot tidak menumpuk). */
suspend fun deleteSession(sessionId: String) {
    runCatching { call(deleteReq("/api/session/$sessionId")) }
}
```

Tidak melempar: kegagalan hapus tidak boleh menggagalkan jawaban yang sudah jadi.

---

## Task 4 — `ChatbotBridge`

**File baru:** `android/app/src/main/java/com/arkacode/app/net/ChatbotBridge.kt`

```kotlin
class ChatbotBridge(
    port: Int,                 // 4098
    private val upstreamPort: Int,   // port opencode
    private val password: String,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response

    // internal
    private fun authorized(headers: Map<String, String>): Boolean
    private suspend fun models(): String
    private suspend fun chat(body: String): String   // JSON OpenAI / error
    private fun runBlockingWithTimeout(block: suspend () -> String): String
}
```

Aturan:

| Metode | Path | Hasil |
|---|---|---|
| GET | `/v1/models`, `/models` | `{"object":"list","data":[{"id":"<providerID>/<id>","object":"model","created":0,"owned_by":"<providerID>"}]}` |
| POST | `/v1/chat/completions`, `/chat/completions` | JSON completion atau SSE |
| OPTIONS | semua | 204 + header CORS (tanpa ini browser client gagal preflight) |
| lain | — | 404 JSON error |

- **Auth:** header `Authorization: Bearer <password>` atau Basic
  (`username` bebas, password sama). Salah/kosong → 401 JSON OpenAI error.
  Password dibandingkan konstan-waktu sederhana (`MessageDigest.isEqual`).
- **Body:** `session.parseBody(map)` → `map["postData"]`; parse dengan
  `ChatbotWire`; body invalid → 400.
- **Model:** `ChatbotWire.splitModel(model)`; tanpa `/` → pilih model pertama
  dari `listModels()` (klien yang tidak scan tetap bisa chat).
- **Alur chat (stateless):**
  1. `createSession()` → `switchModel(provider, id)` (bila ada) → `sendPrompt(prompt)`
  2. Jalankan `waitIdle()` dan `pollApprovals()` bersamaan:
     `pollApprovals` tiap 1 dtk: form `websearch.provider` → jawab opsi
     `value == "allow"`; form lain → `rejectQuestion`; permission dengan
     `summary.startsWith("websearch")` → `always`, lainnya → `reject`.
  3. Setelah idle, `listMessages()` → ambil `ChatItem.Assistant` terakhir,
     gabung `texts` dengan `\n\n`. Kosong → teks error yang jelas.
  4. `deleteSession()` di `finally`.
- **Batas waktu:** seluruh alur `withTimeoutOrNull(180_000)`; habis → 504 JSON.
- **SSE (`stream: true`):** content-type `text/event-stream`; kirim
  `chat.completion.chunk` (role), satu delta isi penuh, `finish_reason:"stop"`,
  lalu `data: [DONE]`. Bukan streaming token-per-token — catat sebagai
  `shortcut:` di kode.
- **Usage:** `{prompt_tokens:0, completion_tokens:0, total_tokens:0}`.
- **CORS:** `Access-Control-Allow-Origin: *`, `-Headers: authorization,
  content-type`, `-Methods: GET, POST, OPTIONS` di setiap response.
- `start(SOCKET_READ_TIMEOUT, true)` (daemon) dipanggil EngineManager.

Catatan implementasi NanoHTTPD 2.3.1:
- `newFixedLengthResponse(status, mime, body)`.
- `session.parseBody(HashMap())` untuk POST; jangan panggil untuk GET.
- `serve` berjalan di thread NanoHTTPD → bungkus suspend dengan
  `runBlocking { withTimeoutOrNull(...) { ... } }`.

---

## Task 5 — Lifecycle di `EngineManager`

**File:** `android/app/src/main/java/com/arkacode/app/core/EngineManager.kt`

1. `EngineConfig`: `const val CHATBOT_PORT = 4098`.
2. `@Volatile private var bridge: ChatbotBridge? = null`.
3. `fun chatbotEnabled(): Boolean = SecureStore.getBool(context, "chatbot", false)`.
4. `fun setChatbot(enabled: Boolean, port: Int, password: String)`:
   `SecureStore.setBool(...)`, `stopBridge()`, lalu bila `enabled` →
   `startBridge(port, password)`. Port/password datang dari UI supaya kasus
   "server sudah jalan dari sesi sebelumnya" ikut tertangani.
5. `fun startBridge(port: Int, password: String)`: sudah ada bridge → return;
   password kosong → return; buat & `start(SOCKET_READ_TIMEOUT, true)`.
6. `fun syncChatbot(port: Int, password: String)`: bila flag aktif →
   `stopBridge()` + `startBridge(...)` (memakai kredensial terbaru).
7. `startServer`: setelah sehat, panggil `syncChatbot(port, password)`.
8. `stopServer`: `stopBridge()` paling awal.
9. Cabang resume di `MainScreen` memanggil `engine.syncChatbot(port, password)`
   setelah status JALAN.
10. Kegagalan bind bridge (mis. port 4098 dipakai) → catat ke `serve.log`,
    jangan gagalkan server opencode; `bridge = null` lagi.

---

## Task 6 — UI toggle

**File:** `android/app/src/main/java/com/arkacode/app/MainScreen.kt`

1. State: `var chatbot by remember { mutableStateOf(SecureStore.getBool(ctx, KEY_CHATBOT, false)) }`
   dengan `KEY_CHATBOT = "chatbot"`.
2. `ServerTab` tambah parameter `chatbot: Boolean`, `onChatbotChange: (Boolean) -> Unit`.
3. Di dalam `ServerTab` setelah kartu URL, `ClayCard`:
   - baris: judul **"Mode Chatbot (OpenAI API)"** + `Switch(checked, onCheckedChange)`.
   - keterangan: `http://<ip-hp>:4098/v1` — "untuk client OpenAI-compatible
     (model + chat). Web search otomatis aktif. Tool file/shell ditolak."
   - tombol Salin memakai `onCopy("http://127.0.0.1:4098/v1")`.
4. Callback di `MainScreen`: `onChatbotChange = { b -> chatbot = b; engine.setChatbot(b, port, password) }`.
5. Cabang resume (`LaunchedEffect` saat `healthy(port, password)`) memanggil
   `engine.syncChatbot(port, password)` supaya bridge ikut menyala setelah app
   di-restart.

---

## Task 7 — README

**File:** `README.md`

Tambah bagian "Mode chatbot (OpenAI-compatible)" berisi:
- aktifkan switch di tab Server;
- base URL `http://<ip>:4098/v1`, API key = password server;
- contoh `curl` `/v1/models` dan `/v1/chat/completions`;
- web search keyless otomatis; tool file/shell ditolak;
- `stream:true` didukung tetapi bukan token-per-token.

---

## Verifikasi akhir (di mesin pengguna)

1. `./gradlew :app:testDebugUnitTest`
2. `./gradlew :app:assembleDebug`
3. Install → start server → nyalakan switch chatbot.
4. `curl -H "Authorization: Bearer <pw>" http://127.0.0.1:4098/v1/models` → daftar model.
5. `curl ... /v1/chat/completions -d '{"model":"<id>","messages":[{"role":"user","content":"halo"}]}'` → jawaban.
6. Minta "cari berita terbaru X" → ada hasil web, tanpa key.
7. Matikan server / switch → port 4098 tidak lagi listen.
8. Web UI opencode di 4097 tetap normal.

## Di luar scope (jangan dikerjakan)

- Streaming token-per-token, `/v1/embeddings`, riwayat sesi, multi-turn state.
- Kolom API key web search (keyless sudah terbukti).
- Perubahan perilaku mode agent.
