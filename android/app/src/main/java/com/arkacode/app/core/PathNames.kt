package com.arkacode.app.core

/**
 * Nama tampilan terakhir dari sebuah path.
 *
 * Server opencode mengirim path **direktori dengan pemisah akhir**
 * (mis. `"src/"` di Linux, `"src\\"` di Windows); file tidak. Mengiris
 * langsung dengan [String.substringAfterLast] pada `"src/"` menghasilkan
 * string kosong, sehingga nama folder tampil kosong. Helper ini membuang
 * pemisah akhir dulu (baik `/` maupun `\`).
 *
 * `fileLeafName("src/")` → `"src"`, `fileLeafName("a/b.txt")` → `"b.txt"`.
 */
fun fileLeafName(path: String): String {
    val trimmed = path.trimEnd('/', '\\')
    val leaf = trimmed.substringAfterLast('/').substringAfterLast('\\')
    return leaf.ifBlank { trimmed.ifBlank { path } }
}

/**
 * Normalisasi path dari server `/api/fs/list` & `/api/fs/find`: direktori
 * dikirim dengan pemisah akhir (`"src/"`), file tidak. Pemisah akhir itu
 * bukan bagian dari path logis, jadi dibuang di satu tempat agar semua
 * konsumen (UI, navigasi `cwd`, kunci LazyColumn) menerima path bersih.
 *
 * `normalizeFsPath("src/")` → `"src"`, `normalizeFsPath("a/b.txt")` → `"a/b.txt"`.
 */
fun normalizeFsPath(path: String): String = path.trimEnd('/', '\\')
