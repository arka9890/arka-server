# Arka Server

App Android **headless** (host server opencode di HP) — pemisahan dari proyek
Arkacode yang sengaja dibuat supaya Arkacode utuh tetap memakai perannya
sebagai app AI coding on-device. ArkaServer hanya satu hal: menjalankan
engine opencode supaya web UI bawaan `opencode serve` bisa diakses dari
browser **device mana saja**.

## Arsitektur

```
App Android (FGS EngineService wakelock)
  └─ proot Alpine (DistroManager) → opencode serve
       --hostname 0.0.0.0 --port 4097
       = API v2 (/api/...) + WEB UI bawaan (satu server, satu layar)
Tailscale (native Android)  → http://<tailscale-ip-hp>:4097 dari device lain
Password: basic auth (OPENCODE_SERVER_PASSWORD ← SecureStore)
```

## Fitur

- **Tab Server** — status (JALAN/MULAI/MATI, versi + uptime), URL (splain),
  start/stop besar, hint Tailscale + battery optimization.
- **Tab Log** — `serve.log` monospace, Salin/Refresh.
- **Tab Pengaturan** — password (+ generasikan acak kuat) mempersulit "berlaku
  setelah restart", port, mulai-otomatis, dan blok engine:
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

Bila npm-registry jaringan mati, build tetap berjalan (binary proot dibawa)
seperti di Arkacode.

## Proses server pertama kali

MULAI → download Alpine minirootfs (±4 MB) → opencode pinned `v2.0.31`
(±60 MB dari npm, verifikasi sha512) → `serve`. Log tampil di layar Log.

## Tailscale (di luar app)

1. Google Play: Install Tailscale di HP server + device klien (satu tailnet).
2. Battery optimization exempt untuk Arka Server + Tailscale.
3. Browser → `http://<tailscale-ip-hp>:4097` → login user `opencode` +
   password dari tab Pengaturan. URL dicetak depannya Tab Server.

## Ingat

- bind 0.0.0.0 juga mengekspos port 4097 di Wi-Fi lokal — password kuat
  wajib; jangan forward port ke internet publik.
- server mati = HP mati; Doze bisa bunuh FGS bila tidak battery-exempt.
