package com.arkacode.app.core

import android.content.Context
import java.io.File

/**
 * Menjembatani token GitHub (nanti dari layar Providers/Auth) ke dalam distro
 * proot, supaya tool bash opencode bisa `git clone/push/pull` langsung
 * terautentikasi — tanpa tempel token di chat.
 *
 * Mekanisme: `/root/.git-credentials` di rootfs + `credential.helper = store`
 * di `/root/.gitconfig` global, format `x-access-token` (tanpa username).
 */
object GitCredentials {
    private const val CRED_PATH = "root/.git-credentials"
    private const val CONFIG_PATH = "root/.gitconfig"

    private fun rootfsDir(context: Context): File =
        File(File(context.filesDir, "distro"), "alpine")

    /** Sinkronkan kredensial proot dengan [token] (kosong = cabut). */
    fun sync(context: Context, token: String) {
        runCatching {
            val root = rootfsDir(context)
            if (!File(root, "etc/alpine-release").isFile && !File(root, "bin/busybox").isFile) return
            val clean = token.trim()
            val cred = File(root, CRED_PATH)
            if (clean.isEmpty()) {
                cred.delete()
                return
            }
            if (clean.any { it.isWhitespace() || it < ' ' }) return
            File(root, "root").mkdirs()
            cred.writeText("https://x-access-token:$clean@github.com\n")
            cred.setReadable(false, false)
            cred.setReadable(true, true)
            cred.setWritable(false, false)
            cred.setWritable(true, true)
            cred.setExecutable(false, false)
            ensureGitconfig(root)
        }
    }

    private fun ensureGitconfig(root: File) {
        val cfg = File(root, CONFIG_PATH)
        val current = runCatching { cfg.readText() }.getOrDefault("")
        val sb = StringBuilder(current)
        if (!current.contains("helper = store")) {
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n")
            sb.append("[credential]\n\thelper = store\n")
        }
        if (!current.contains("[user]")) {
            sb.append("[user]\n\tname = arkacode\n\temail = arkacode@local\n")
        }
        if (sb.toString() != current) {
            runCatching { cfg.writeText(sb.toString()) }
        }
    }
}
