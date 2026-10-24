# Desain: Mode Endpoint Chatbot (OpenAI-compatible)

Tanggal: 2026-10-23
Status: menunggu persetujuan

## Tujuan

App ini sudah menjalankan `opencode serve` (agent + web UI) di port 4097. Client
yang dibangun dengan **OpenAI API** tidak bisa memakainya karena opencode sama
sekali tidak punya `/v1/*` (diverifikasi di spec v2.0.31: 117 path, semuanya
`/api/*`).

Tambahkan **bridge kecil di dalam APK** supaya client OpenAI-compatible bisa:

- scan model: `GET /v1/models`
- chat: `POST /v1/chat/completions` (termasuk `stream: true`)

Di sisi user tampil sebagai **mode kedua**: switch "Endpoint chatbot (OpenAI)"
di tab Server. Mode agent yang sekarang tetap jalan; bridge hanya pintu tambahan
ke engine yang sama.

## Non-goals

- Bukan pengganti web UI opencode; keduanya hidup bersamaan.
- Tidak mendukung `function calling`, `tools`, `embeddings`, `images`, `audio`.
- Tidak ada manajemen sesi lintas request (client OpenAI mengirim ulang riwayat).
- Tidak menyentuh engine opencode / binary proot.

## Web search di mode chatbot

Tool `websearch` tetap ada karena bridge memakai engine yang sama, dan **tidak
perlu API key**. opencode v2.0.31 sudah mendaftarkan lima provider (Exa,
Firecrawl, Parallel, Tavily, TinyFish); minimal Exa dan TinyFish menerima
pencarian **keyless** — sudah saya uji langsung dari luar (search "latest bun
release" berhasil tanpa kredensial apa pun). Jadi di web UI memang cukup
"Allow".

Yang membuatnya tidak jalan di sesi non-interaktif ada dua:

1. Pemakaian pertama memunculkan **form** "Allow search via …" (timeout 60
   detik). Client OpenAI tidak bisa menjawab form.
2. Tool meminta **permission** (default `ask`). Tanpa jawaban, agent menggantung.

Karena itu bridge ikut memantau `GET /api/session/{id}/form` dan
`GET /api/session/{id}/permission` selama menunggu:

- form dengan metadata `websearch.provider` → jawab `allow` (provider `random`)
  otomatis, sekali saja — hasilnya tersimpan di server, sama seperti
  klik manual di web UI;
- permission `action == "websearch"` → `always`;
- permission tool lain → `reject` (mode chatbot tidak boleh mengubah file /
  menjalankan shell; pakai mode agent untuk itu);
- form lain (mis. agent balik bertanya) → dibatalkan, supaya request tidak
  menggantung menunggu jawaban yang tidak akan datang.

Bila jaringan/penyedia search sedang gagal, tool balik dengan error dan model
tetap menjawab tanpa hasil web — bukan hang.

## Cara kerja

```
client OpenAI ──HTTP :4098/v1──► ChatbotBridge (Kotlin, di APK)
                                      │ OkHttp (Basic auth)
                                      ▼
                              opencode serve :4097/api/*
```

- Port tetap **4098**, bind `0.0.0.0` (sama seperti server utama).
- **Auth**: `Authorization: Bearer <password server>` (atau Basic yang sama).
  Password salah → 401 JSON ala OpenAI.
- **Toggle**: switch di tab Server, tersimpan di `SecureStore`. Saat server
  menyala dan switch aktif → bridge start; server mati / switch off → bridge stop.
  Toggle saat server jalan langsung berlaku (tanpa restart server).

### Kontrak endpoint

`GET /v1/models` (juga `/models`)
→ baca `/api/model`, balas `{"object":"list","data":[{"id":"<providerID>/<modelID>","object":"model","created":0,"owned_by":"<providerID>"}]}`.

`POST /v1/chat/completions` (juga `/chat/completions`), body:

```json
{"model":"<providerID>/<modelID>","messages":[{"role":"system|user|assistant","content":"..."}],"stream":false}
```

Langkah di bridge:
1. `POST /api/session` → session baru (stateless per request).
2. `POST /api/session/{id}/model` bila `model` dikirim (split pada `/` pertama).
3. Rangkai `messages` menjadi satu prompt teks berurutan peran
   (`system`, `user`, `assistant`); kirim `POST /api/session/{id}/prompt`.
4. `POST /api/experimental/session/{id}/wait` (long-poll sampai agent idle).
5. Ambil pesan terakhir role assistant dari `GET /api/session/{id}/message`.
6. `DELETE /api/session/{id}` (jangan biarkan sesi menumpuk).
7. Balas format `chat.completion` (`usage` diisi 0 karena tidak tersedia).

`stream: true`: balasan **satu potong** dalam format SSE
(`role` → `content` penuh → `finish_reason:"stop"` → `[DONE]`), bukan token
per token. Cukup untuk mayoritas client; lihat Batasan.

Error (4xx/5xx) → `{"error":{"message":"...","type":"..."}}`.

## Batasan yang disadari

- opencode adalah **agent**: tiap request menjalankan loop agent di workspace
  (bisa pakai tool, bisa lambat). Client melihatnya sebagai satu jawaban chat.
- `stream: true` tidak real-time (satu chunk). `shortcut: streaming satu potong;
  upgrade ke SSE `/api/event` kalau client menuntut token-per-token.`
- `usage` token diisi 0 — sebagian client menampilkan biaya kosong.
- Client dengan timeout < durasi kerja agent akan terputus; bukan bug bridge.
- HTTP polos; aman hanya di Tailscale/Wi-Fi tepercaya.
- Web search berjalan tanpa kunci (provider keyless); bridge yang menjawab form
  dan permission-nya. Permission tool lain ditolak (chatbot tidak mengubah
  file), jadi jangan harap bisa minta bot "edit file" dari mode ini — itu tugas
  mode agent.

## File yang disentuh

| File | Perubahan |
|---|---|
| `net/ChatbotBridge.kt` (baru) | server NanoHTTPD + terjemahan OpenAI ↔ opencode, ±200 baris |
| `core/EngineManager.kt` | start/stop bridge mengikuti lifecycle server + toggle |
| `MainScreen.kt` | switch mode di tab Server + tampilkan base URL & keterangan API key |
| `core/SecureStore.kt` | simpan flag `chatbot` |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | dependency `org.nanohttpd:nanohttpd:2.3.1` |
| `net/OpenCodeClient.kt` | `deleteSession()` (satu method) |
| `README.md` | satu bagian: cara pakai mode chatbot |
| unit test JVM | fungsi murni: rangkai prompt + parse `provider/model` |

Dependency baru dipakai karena Android tidak punya HTTP server bawaan; menulis
parser HTTP sendiri lebih berisiko daripada satu library kecil yang matang.

## Verifikasi

1. `./gradlew :app:testDebugUnitTest` — test fungsi murni lolos.
2. Di perangkat: server jalan → switch chatbot ON → dari HP lain:
   `curl -u opencode:<pw> http://<ip>:4098/v1/models` → daftar model.
3. `curl http://<ip>:4098/v1/chat/completions -H 'Authorization: Bearer <pw>'
   -d '{"model":"<id>","messages":[{"role":"user","content":"halo"}]}'`
   → `chat.completion` berisi teks jawaban.
4. Scan model di app OpenAI-compatible → model muncul; kirim chat → dibalas.
5. Web search: minta "cari berita terbaru X" lewat endpoint chatbot → jawaban
   memuat hasil web tanpa mengisi kunci apa pun (form + permission terjawab
   otomatis).
6. Switch OFF → port 4098 mati, mode agent tetap normal.

## Keputusan yang saya ambil (bukan permintaan eksplisit)

- Bridge sebagai **tambahan**, bukan mode eksklusif — opencode tetap harus jalan
  karena bridge memanggilnya. Switch hanya menyalakan/mematikan pintu 4098.
- Auth memakai password server yang sudah ada, tanpa kunci baru.
- Model id memakai format `providerID/modelID` — sama dengan id dari `/v1/models`,
  sehingga round-trip scan → chat konsisten.
