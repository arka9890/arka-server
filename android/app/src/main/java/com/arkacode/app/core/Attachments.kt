package com.arkacode.app.core

/**
 * Berkas lampiran siap kirim ke server.
 *
 * [uri] memakai skema `data:<mime>;base64,` — bukan `file:`. OpenCode 1.19
 * meneruskan URL `file:` apa adanya ke protokol OpenAI Chat sehingga gambar
 * ditolak dengan "OpenAI Chat media must contain valid base64", sedangkan
 * `data:` lolos pemeriksaan media dan sampai ke model.
 */
data class Attachment(val name: String, val uri: String, val mime: String)
