# Arka Server

App Android **headless** (host server opencode di HP) — pemisahan dari proyek
Arkacode yang sengaja dibuat supaya Arkacode utuh tetap memakai perannya
sebagai app AI coding on-device. ArkaServer hanya satu hal: menjalankan
engine opencode supaya web UI bawaan `opencode serve` bisa diakses dari
browser **device mana saja**.

## Arsitektur

```
App Android (FGS EngineService wakelock)
  ├─ OpenAI Bridge Server (:4098)
  │    └─ GET  /v1/models
  │    └─ POST /v1/chat/completions (streaming SSE & non-streaming)
  │         └─ Untuk: Cursor, Cline, Continue.dev, LibreChat, Python, dll.
  │
  └─ proot ubuntu-22.04 (DistroManager) → opencode serve (:4097)
       --hostname 0.0.0.0 --port 4097
       = API v2 (/api/...) + WEB UI bawaan (satu server, satu layar)

Tailscale (native Android) →
  - Web UI: http://<tailscale-ip-hp>:4097
  - OpenAI API: http://<tailscale-ip-hp>:4098/v1
Password / API Key: OPENCODE_SERVER_PASSWORD (tersimpan di SecureStore)
```

## Fitur

- **Tab Server** — status (JALAN/MULAI/MATI, versi + uptime), URL Web UI (:4097),
  URL OpenAI API (:4098/v1), start/stop besar, hint Tailscale + battery optimization.
  Pesan hasil aksi — `GAGAL: …`, `password tidak boleh kosong`, `server dihentikan` —
  tampil di sini **utuh tanpa dipotong**, karena ini balasan langsung atas tombol
  yang barusan ditekan.
- **OpenAI-Compatible Bridge (:4098)** — mengekspos endpoint standar
  `/v1/chat/completions` dan `/v1/models` agar bisa disambungkan langsung ke tool
  seperti Cursor, VS Code Cline, Continue.dev, LibreChat, Chatbox, atau script OpenAI SDK.
  Mendukung streaming SSE, reasoning content, multi-turn, dan multimodal gambar.
- **Tab Log** — progres/teknis pindah ke sini (dulu menumpuk di halaman depan):
  `distro 45%`, `engine 12%`, unduhan, cek pembaruan. Di bawahnya `serve.log`
  monospace **tampil penuh tanpa dipotong** (sebelumnya hanya 400 baris terakhir,
  sehingga stack trace proot yang dibutuhkan saat debug sering hilang), lengkap
  dengan Salin/Refresh/Hapus.
- **Tab Pengaturan** — password (+ generasikan acak kuat), port Web UI & port OpenAI bridge,
  toggle OpenAI API bridge, mulai-otomatis, sinkronisasi token GitHub PAT, dan blok engine:
  update otomatis (tutup/nyala), update manual (cek+ unduh+ verifikasi
  sha512+ swap atomik), dan **rollback** dari `.bak` (update data selalu
  meninggalkan backup).

UI: reusing design system dari claymorphism dari Arkacode (Theme.kt + Clay.kt).

## Build

Persyaratan sama dengan Arkacode: JDK 21, Android SDK 35.

```sh
./gradlew :app:assembleDebug -p android        # atau pindahkan ke folder android/
./gradlew :app:testDebugUnitTest -p android    # EngineReleaseTest
```

### Upgradeable (tanpa uninstall)

Semua build (debug maupun release) **ditandatangani keystore bersama yang
committed** (`arkacode.keystore`) — tanda tangan identik antar build/mesin, jadi
APK baru terpasang langsung di atas yang lama: data (rootfs, engine
opencode, password tersimpan) tidak hilang. `versionCode` otomatis dari
`GITHUB_RUN_NUMBER` di CI; bila membangun manual, angkat manual di
`app/build.gradle.kts` bila perlu.

Bila keystore berubah, Android akan menolak update — jangan ganti
`arkacode.keystore`.


Bila npm-registry jaringan mati, build tetap berjalan (binary proot dibawa)
seperti di Arkacode.

## Proses server pertama kali

MULAI → download ubuntu-base 22.04 (±30 MB) → pustaka tamu via apt (dilewati
bila sudah terpasang — dicek dulu, tidak menjalankan apt ulang yang tak perlu)
→ opencode terbaru dari npm (fallback pin `v2.0.31`, ±60 MB, verifikasi sha512)
→ `serve`. Log tampil di layar Log. Rootfs lama (Alpine) terdeteksi sendiri
dan diganti saat boot.

## Tailscale (di luar app)

1. Google Play: Install Tailscale di HP server + device klien (satu tailnet).
2. Battery optimization exempt untuk Arka Server + Tailscale.
3. Browser → `http://<tailscale-ip-hp>:4097` → login user `opencode` +
   password dari tab Pengaturan. URL dicetak depannya Tab Server.

## Akses OpenAI-compatible API dari Device Lain

Anda bisa menyambungkan berbagai client AI ke Arka Server:

- **Base URL**: `http://<ip-tailscale-atau-wifi-hp>:4098/v1`
- **API Key**: Password server Anda dari Tab Pengaturan (diisi di kolom API Key / Bearer token)

### Contoh Client:

#### 1. Python (Official OpenAI SDK)
```python
from openai import OpenAI

client = OpenAI(
    base_url="http://192.168.1.50:4098/v1",  # atau IP Tailscale HP Anda
    api_key="password-server-anda",
)

# Non-streaming
response = client.chat.completions.create(
    model="anthropic/claude-3-7-sonnet", # atau model apa pun yang aktif di opencode
    messages=[{"role": "user", "content": "Halo dari laptop!"}],
)
print(response.choices[0].message.content)

# Streaming
stream = client.chat.completions.create(
    model="anthropic/claude-3-7-sonnet",
    messages=[{"role": "user", "content": "Buatkan fungsi binary search di Python"}],
    stream=True,
)
for chunk in stream:
    if chunk.choices[0].delta.content:
        print(chunk.choices[0].delta.content, end="", flush=True)
```

#### 2. VS Code (Cline / Roo Code / Continue.dev)
- Provider: `OpenAI-compatible`
- Base URL: `http://<ip-hp>:4098/v1`
- API Key: `password-server-anda`
- Model ID: `anthropic/claude-3-7-sonnet` (atau model yang tersedia dari `GET /v1/models`)

#### 3. Cursor / Windsurf / LibreChat / NextChat
- Masukkan URL API kustom: `http://<ip-hp>:4098/v1`
- Masukkan API Key: password server Anda.

## Ingat

- bind 0.0.0.0 juga mengekspos port 4097 di Wi-Fi lokal — password kuat
  wajib; jangan forward port ke internet publik.
- server mati = HP mati; Doze bisa bunuh FGS bila tidak battery-exempt.
