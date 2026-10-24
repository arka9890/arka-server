package com.arkacode.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.arkacode.app.core.EngineConfig
import com.arkacode.app.core.EngineManager
import com.arkacode.app.core.EngineService
import com.arkacode.app.core.GitCredentials
import com.arkacode.app.core.SecureStore
import com.arkacode.app.net.OpenCodeClient
import com.arkacode.app.ui.ClayBottomBar
import com.arkacode.app.ui.ClayCard
import com.arkacode.app.ui.ClayNavItem
import com.arkacode.app.ui.ClaySectionTitle
import com.arkacode.app.ui.ClayShapeSmall
import com.arkacode.app.ui.ClaySurface
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI headless Arkacode-arca-server: tiga tab (Server, Log, Pengaturan).
 * TIDAK ada chat/web-UI di app ini — semua UI asli disajikan oleh
 * web UI bawaan `opencode serve`, dibuka lewat browser.
 */

private const val KEY_AUTOBOOT = "autoboot"
private const val KEY_AUTO_UPDATE = "engine_auto_update"

private enum class Tab(val label: String) { SERVER("Server"), LOG("Log"), SETTINGS("Pengaturan") }

private enum class Status { MATI, MULAI, JALAN }

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val engine = remember { EngineManager(ctx) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // ---- kredensial (SecureStore) ----
    val creds = remember { SecureStore.load(ctx) }
    var port by remember { mutableStateOf(creds?.first ?: EngineConfig.DEFAULT_PORT) }
    var password by remember { mutableStateOf(creds?.second?.takeIf { it.isNotBlank() } ?: randomPassword()) }
    var gitPat by remember { mutableStateOf(SecureStore.getGitPat(ctx) ?: "") }
    var autoboot by remember { mutableStateOf(SecureStore.getBool(ctx, KEY_AUTOBOOT, false)) }
    var autoUpdate by remember { mutableStateOf(SecureStore.getBool(ctx, KEY_AUTO_UPDATE, true)) }
    var bridgeEnabled by remember { mutableStateOf(SecureStore.isBridgeEnabled(ctx)) }
    var bridgePort by remember { mutableStateOf(SecureStore.getBridgePort(ctx)) }

    // ---- status server ----
    var status by remember { mutableStateOf(Status.MATI) }
    var serverVersion by remember { mutableStateOf<String?>(null) }
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var uptimeMs by remember { mutableStateOf(0L) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var progressLine by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(Tab.SERVER) }

    fun persist() = SecureStore.save(ctx, port, password)
    // Progres/teknis (unduhan, persentase) → tab Log; hasil aksi — sukses,
    // gagal, validasi — tetap di tab Server karena balasan atas tombol yang
    // barusan ditekan di sana.
    fun slog(s: String) { progressLine = s }
    fun alert(s: String) { statusLine = s }
    fun copy(text: String, label: String) {
        clipboard?.setText(AnnotatedString(text))
        alert("$label disalin.")
    }

    // Kredensial bawaan (belum pernah disimpan) langsung di-persist.
    LaunchedEffect(Unit) {
        if (creds == null) SecureStore.save(ctx, port, password)
        // Sinkronkan PAT ke rootfs (takutnya distro dipasang ulang / pertama).
        if (gitPat.isNotBlank()) runCatching { GitCredentials.sync(ctx, gitPat) }
    }

    suspend fun healthy(port: Int, pass: String): Boolean =
        runCatching { OpenCodeClient(port = port, password = pass).health() }
            .getOrNull()?.healthy == true

    // Pipeline boot — pola dari EngineScreen Arkacode asli:
    // cek server lama -> distro -> engine + auto-update -> startServer -> health.
    suspend fun boot() {
        status = Status.MULAI
        try {
            slog("cek server lama di port $port…")
            val alive = runCatching { OpenCodeClient(port = port, password = password).health() }.getOrNull()
            if (alive?.healthy == true) {
                startedAt = System.currentTimeMillis()
                serverVersion = alive.version
                status = Status.JALAN
                alert("server sudah jalan v${alive.version}.")
                runCatching { EngineService.start(ctx, port, alive.version) }
                if (bridgeEnabled) {
                    engine.startBridge(bridgePort, port, password)
                }
                return
            }
            val distro = withContext(Dispatchers.IO) { engine.distroStatus() }
            if (!distro.installed) {
                slog("mengunduh Ubuntu (±30 MB)…")
                engine.setupDistro { p, label -> slog("distro $p% — $label") }.getOrThrow()
            }
            val downloaded = withContext(Dispatchers.IO) { engine.isDownloaded() }
            // Binary lama (mis. flavor musl dari rootfs Alpine sebelumnya) tidak
            // jalan di rootfs ubuntu — terdeteksi sebagai version() null walau
            // file ada → unduh ulang kandidat gnu.
            val broken = downloaded && withContext(Dispatchers.IO) { engine.version() == null }
            if (!downloaded || broken) {
                if (broken) slog("engine lama tidak jalan di rootfs baru, mengunduh ulang…")
                slog("mengunduh opencode (±60 MB)…")
                var last = -1
                val v = engine.installLatest(
                    onProgress = { p -> if (p / 10 != last / 10) { last = p; slog("engine $p%") } },
                    onLog = ::slog,
                ).getOrThrow()
                slog("engine $v siap.")
            } else if (autoUpdate) {
                slog("cek pembaruan opencode…")
                val u = engine.checkEngineUpdate()
                if (u?.hasUpdate == true) {
                    slog("pembaruan ${u.current ?: "?"} → ${u.latest}, mengunduh…")
                    var last = -1
                    engine.updateEngineNow(
                        onProgress = { p -> if (p / 10 != last / 10) { last = p; slog("update $p%") } },
                        onLog = ::slog,
                    )
                }
            }
            slog("menyalakan server di port $port…")
            engine.startServer(port = port, password = password).getOrThrow()
            val h = runCatching { OpenCodeClient(port = port, password = password).health() }.getOrNull()
                ?: throw IllegalStateException("server tidak sehat setelah start")
            startedAt = System.currentTimeMillis()
            serverVersion = h.version
            status = Status.JALAN
            alert("server jalan v${h.version}.")
            runCatching { EngineService.start(ctx, port, h.version) }
        } catch (e: Exception) {
            runCatching { engine.stopServer() }
            EngineService.stop(ctx)
            status = Status.MATI
            alert("GAGAL: ${e.message ?: "tidak diketahui"}")
        }
    }

    // ---- tombol utama start/stop ----
    fun onMainButton() {
        when (status) {
            Status.MULAI -> Unit
            Status.JALAN -> scope.launch {
                status = Status.MULAI
                withContext(Dispatchers.IO) { engine.stopServer() }
                EngineService.stop(ctx)
                startedAt = null
                serverVersion = null
                status = Status.MATI
                alert("server dihentikan.")
            }
            Status.MATI -> {
                if (password.isBlank()) {
                    alert("password tidak boleh kosong — isi dulu di tab Pengaturan.")
                    return
                }
                persist()
                scope.launch { boot() }
            }
        }
    }

    // Status awal: server sisa sesi sebelumnya (lakukan resume) atau autoboot.
    LaunchedEffect(Unit) {
        if (healthy(port, password)) {
            startedAt = System.currentTimeMillis()
            serverVersion = runCatching { OpenCodeClient(port = port, password = password).health().version }.getOrNull()
            status = Status.JALAN
            runCatching { EngineService.start(ctx, port, serverVersion ?: "?") }
            if (bridgeEnabled) {
                engine.startBridge(bridgePort, port, password)
            }
        } else if (SecureStore.getBool(ctx, KEY_AUTOBOOT, false)) {
            boot()
        }
    }

    // Watchdog: bila server mati sendiri, badge & service ikut menyesuaikan.
    LaunchedEffect(status) {
        if (status != Status.JALAN) return@LaunchedEffect
        var failures = 0
        while (status == Status.JALAN) {
            delay(5_000)
            if (healthy(port, password)) failures = 0 else failures++
            if (failures >= 2) {
                withContext(Dispatchers.IO) { engine.stopServer() }
                EngineService.stop(ctx)
                startedAt = null
                serverVersion = null
                status = Status.MATI
                alert("server tidak lagi merespons.")
            }
        }
    }

    // Uptime ticker.
    LaunchedEffect(status, startedAt) {
        val t0 = startedAt
        if (status == Status.JALAN && t0 != null) {
            while (status == Status.JALAN) {
                uptimeMs = System.currentTimeMillis() - t0
                delay(1_000)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding(),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                Tab.SERVER -> ServerTab(
                    status = status,
                    port = port,
                    bridgeEnabled = bridgeEnabled,
                    bridgePort = bridgePort,
                    serverVersion = serverVersion,
                    uptimeMs = uptimeMs,
                    statusLine = statusLine,
                    onCopy = { copy(it, "URL") },
                    onMain = ::onMainButton,
                )
                Tab.LOG -> LogTab(
                    progressLine = progressLine,
                    engine = engine,
                    onCopy = { copy(it, "Log") },
                )
                Tab.SETTINGS -> SettingsTab(
                    ctx = ctx,
                    engine = engine,
                    port = port,
                    password = password,
                    running = status == Status.JALAN,
                    autoboot = autoboot,
                    autoUpdate = autoUpdate,
                    bridgeEnabled = bridgeEnabled,
                    bridgePort = bridgePort,
                    onPasswordChange = { password = it; persist() },
                    onPortChange = { p -> port = p; persist() },
                    onRandomPassword = { password = randomPassword(); persist() },
                    gitPat = gitPat,
                    onGitPatChange = { pat ->
                        // Sudah di-trim; kosong = cabut kredensial distro.
                        gitPat = pat
                        runCatching { SecureStore.setGitPat(ctx, pat) }
                        if (!pat.contains(' ')) runCatching { GitCredentials.sync(ctx, pat) }
                    },
                    onAutobootChange = { b -> autoboot = b; SecureStore.setBool(ctx, KEY_AUTOBOOT, b) },
                    onAutoUpdateChange = { b -> autoUpdate = b; SecureStore.setBool(ctx, KEY_AUTO_UPDATE, b) },
                    onBridgeEnabledChange = { b -> bridgeEnabled = b; SecureStore.setBridgeEnabled(ctx, b) },
                    onBridgePortChange = { p -> bridgePort = p; SecureStore.setBridgePort(ctx, p) },
                )
            }
        }
        ClayBottomBar(
            items = listOf(
                ClayNavItem(Icons.Filled.Home, "Server", tab == Tab.SERVER) { tab = Tab.SERVER },
                ClayNavItem(Icons.AutoMirrored.Filled.List, "Log", tab == Tab.LOG) { tab = Tab.LOG },
                ClayNavItem(Icons.Filled.Settings, "Pengaturan", tab == Tab.SETTINGS) { tab = Tab.SETTINGS },
            ),
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

// ------------------------------------------------------------------ Tab: Server

@Composable
private fun ColumnScope.ServerTab(
    status: Status,
    port: Int,
    bridgeEnabled: Boolean,
    bridgePort: Int,
    serverVersion: String?,
    uptimeMs: Long,
    statusLine: String?,
    onCopy: (String) -> Unit,
    onMain: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Arka Server",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        StatusBadge(status)
    }
    if (statusLine != null) {
        Text(
            statusLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Tanpa maxLines: kini hanya berisi hasil aksi (sukses/gagal/validasi),
            // dan pesan GAGAL justru harus terbaca utuh — dulu terpotong ellipsis
            // karena baris ini masih menampung progres.
        )
    }

    ClayCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (status == Status.JALAN) "http://127.0.0.1:$port" else "—",
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (status == Status.JALAN) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "v${serverVersion ?: "?"} · ${formatUptime(uptimeMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (status == Status.JALAN) {
                        "http://0.0.0.0:$port — Web UI (buka dari browser)"
                    } else {
                        "Web UI: http://127.0.0.1:$port (di HP ini)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onCopy("http://127.0.0.1:$port") }) {
                Text("Salin", modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }

    if (bridgeEnabled) {
        ClayCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (status == Status.JALAN) "http://127.0.0.1:$bridgePort/v1" else "—",
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (status == Status.JALAN) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "OpenAI API",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (status == Status.JALAN) {
                            "http://0.0.0.0:$bridgePort/v1 — untuk Cursor, Cline, Continue, dll."
                        } else {
                            "OpenAI Endpoint: http://127.0.0.1:$bridgePort/v1"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onCopy("http://127.0.0.1:$bridgePort/v1") }) {
                    Text("Salin", modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
    }

    // Tombol besar start/stop — disabled saat MULAI.
    Button(
        onClick = onMain,
        enabled = status != Status.MULAI,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        Text(
            when (status) {
                Status.JALAN -> "Hentikan Server"
                Status.MULAI -> "Menyalakan…"
                Status.MATI -> "Mulai Server"
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }

    ClayCard {
        ClaySectionTitle("Akses dari luar HP")
        Text(
            "Install Tailscale, lalu buka http://0.0.0.0:$port dari browser",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        BatteryRow()
    }
}

/** Badge status: JALAN (hijau) / MULAI (biru) / MATI (merah muda). Status juga utuh di teks badge untuk a11y. */
@Composable
private fun StatusBadge(status: Status) {
    val (bg, fg, text) = when (status) {
        Status.JALAN -> Triple(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary, "JALAN")
        Status.MULAI -> Triple(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, "MULAI")
        Status.MATI -> Triple(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError, "MATI")
    }
    Box(
        modifier = Modifier
            .clip(ClayShapeSmall)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .heightIn(min = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Satu baris battery-optimization: tanpa pengecualian sistem bebas
 * membunuh app (dan ikut menewaskan server) saat layar mati.
 */
@Composable
private fun BatteryRow() {
    val ctx = LocalContext.current
    val pm = remember { ctx.getSystemService(Context.POWER_SERVICE) as PowerManager }
    if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
        Text(
            "✓ Optimasi baterai sudah dikecualikan",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    } else {
        TextButton(
            onClick = {
                runCatching {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${ctx.packageName}"),
                        ),
                    )
                }.onFailure {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("Kecualikan optimasi baterai (agar server tetap hidup)")
        }
    }
}

// ------------------------------------------------------------------- Tab: Log

@Composable
private fun ColumnScope.LogTab(
    progressLine: String?,
    engine: EngineManager,
    onCopy: (String) -> Unit,
) {
    var logText by remember { mutableStateOf("memuat…") }
    var refreshKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshKey) {
        logText = withContext(Dispatchers.IO) {
            val f = engine.logFile()
            runCatching {
                // Full log, tanpa dipotong: debugging sering butuh bagian awal
                // (stack trace / pesan proot) yang `takeLast(N)` buang.
                // shortcut: serve.log tanpa rotasi — kalau filenya pernah tembus
                // ratusan MB, ganti jadi baca mundur N byte terakhir.
                if (f.isFile) f.readLines().joinToString("\n") else "(log kosong)"
            }.getOrDefault("(log tidak terbaca)")
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "serve.log",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onCopy(logText) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Salin") }
        OutlinedButton(onClick = { refreshKey++ }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Refresh")
        }
        // Bersihkan isi log (truncate): baris baru dari server tetap mengalir.
        OutlinedButton(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { engine.clearLog() }
                    refreshKey++
                }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("Hapus")
        }
    }
    // Progres/teknis yang tadinya menumpuk di tab Server; tanpa maxLines biar
    // tak lagi kepotong ellipsis seperti di halaman depan.
    if (progressLine != null) {
        Text(
            progressLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    // Monospace, tick terpilih supaya penyalin cerdas tetap bekerja.
    ClaySurface(shape = com.arkacode.app.ui.ClayShape, elevation = 10.dp) {
        SelectionContainer {
            Text(
                logText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            )
        }
    }
}

// ------------------------------------------------------------ Tab: Pengaturan

@Composable
private fun ColumnScope.SettingsTab(
    ctx: Context,
    engine: EngineManager,
    port: Int,
    password: String,
    running: Boolean,
    autoboot: Boolean,
    autoUpdate: Boolean,
    bridgeEnabled: Boolean,
    bridgePort: Int,
    onPasswordChange: (String) -> Unit,
    onPortChange: (Int) -> Unit,
    onRandomPassword: () -> Unit,
    gitPat: String,
    onGitPatChange: (String) -> Unit,
    onAutobootChange: (Boolean) -> Unit,
    onAutoUpdateChange: (Boolean) -> Unit,
    onBridgeEnabledChange: (Boolean) -> Unit,
    onBridgePortChange: (Int) -> Unit,
) {
    Text(
        "Pengaturan",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )

    var portText by remember(port) { mutableStateOf(port.toString()) }
    var passwordVisible by remember { mutableStateOf(false) }

    ClayCard {
        ClaySectionTitle("Akses")
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password server") },
            supportingText = { Text("Ketik passwordmu sendiri, atau pilih \"Acak kuat\".") },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(
                    onClick = { passwordVisible = !passwordVisible },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (passwordVisible) "Sembunyikan" else "Tampilkan") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRandomPassword, modifier = Modifier.heightIn(min = 48.dp)) { Text("Acak kuat") }
            Text(
                "Simpan langsung di SecureStore",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (running) {
            Text(
                "Perubahan password berlaku setelah server di-restart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = portText,
            onValueChange = { s ->
                portText = s
                s.toIntOrNull()?.takeIf { it in 1024..65535 }?.let(onPortChange)
            },
            label = { Text("Port (1024–65535)") },
            isError = portText.toIntOrNull()?.let { it in 1024..65535 } == false,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (running) {
            Text(
                "Perubahan port berlaku setelah server di-restart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))
        GitHubPatField(
            value = gitPat,
            onValueChange = onGitPatChange,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = autoboot) { onAutobootChange(!autoboot) }
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mulai otomatis saat app dibuka", modifier = Modifier.weight(1f))
            Switch(checked = autoboot, onCheckedChange = onAutobootChange)
        }
    }

    ClayCard {
        ClaySectionTitle("OpenAI-compatible Endpoint")
        Text(
            "Mengizinkan aplikasi luar (Cursor, Cline, Continue.dev, LibreChat, dll.) " +
                "mengakses AI melalui endpoint OpenAI /v1/chat/completions standar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = bridgeEnabled) { onBridgeEnabledChange(!bridgeEnabled) }
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Aktifkan OpenAI API Bridge", modifier = Modifier.weight(1f))
            Switch(checked = bridgeEnabled, onCheckedChange = onBridgeEnabledChange)
        }
        if (bridgeEnabled) {
            Spacer(Modifier.height(8.dp))
            var bridgePortText by remember(bridgePort) { mutableStateOf(bridgePort.toString()) }
            OutlinedTextField(
                value = bridgePortText,
                onValueChange = { s ->
                    bridgePortText = s
                    s.toIntOrNull()?.takeIf { it in 1024..65535 }?.let(onBridgePortChange)
                },
                label = { Text("Port OpenAI API (1024–65535)") },
                isError = bridgePortText.toIntOrNull()?.let { it in 1024..65535 } == false,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (running) {
                Text(
                    "Perubahan port/status bridge berlaku setelah server di-restart.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    ClayCard {
        ClaySectionTitle("Engine")
        EngineBlock(
            ctx = ctx,
            engine = engine,
            running = running,
            autoUpdate = autoUpdate,
            onAutoUpdateChange = onAutoUpdateChange,
        )
    }
}

@Composable
private fun EngineBlock(
    ctx: Context,
    engine: EngineManager,
    running: Boolean,
    autoUpdate: Boolean,
    onAutoUpdateChange: (Boolean) -> Unit,
) {
    var engineVersion by remember { mutableStateOf<String?>(null) }
    var engineBusy by remember { mutableStateOf(false) }
    var engineMsg by remember { mutableStateOf<String?>(null) }
    var hasBak by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun bakFile(): File = File(engine.binaryFile().parentFile, "opencode.bak")

    // Versi engine & status .bak dimuat sekali saat tab dibuka.
    LaunchedEffect(Unit) {
        engineVersion = runCatching { engine.version() }.getOrNull() ?: EngineConfig.VERSION
        hasBak = bakFile().isFile
    }

    Text("Versi engine: v${engineVersion ?: "…"}", style = MaterialTheme.typography.bodyMedium)
    Text(
        "Versi app: ${appVersion(ctx)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = autoUpdate) { onAutoUpdateChange(!autoUpdate) }
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Update otomatis", modifier = Modifier.weight(1f))
        Switch(checked = autoUpdate, onCheckedChange = onAutoUpdateChange)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                scope.launch {
                    engineBusy = true
                    try {
                        when {
                            running -> engineMsg = "Hentikan server dulu sebelum update (proses binary sedang dipakai)."
                            else -> {
                                val u = engine.checkEngineUpdate()
                                when {
                                    u == null -> engineMsg = "Gagal cek pembaruan (jaringan offline?)."
                                    !u.hasUpdate -> engineMsg = "opencode sudah terbaru v${u.latest}."
                                    else -> {
                                        engineMsg = "pembaruan ${u.current ?: "?"} → ${u.latest}…"
                                        engine.updateEngineNow(
                                            onProgress = {},
                                            onLog = { engineMsg = it },
                                        ).fold(
                                            onSuccess = { v -> engineMsg = "engine diperbarui ke v$v." },
                                            onFailure = { e -> engineMsg = "pembaruan gagal: ${e.message ?: "?"}" },
                                        )
                                    }
                                }
                            }
                        }
                    } finally {
                        engineVersion = runCatching { engine.version() }.getOrNull() ?: engineVersion
                        hasBak = bakFile().isFile
                        engineBusy = false
                    }
                }
            },
            enabled = !engineBusy,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(if (engineBusy) "memproses…" else "Periksa & update")
        }
        if (hasBak) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        engineBusy = true
                        try {
                            engine.rollbackUpdate { engineMsg = it }.fold(
                                onSuccess = { v -> engineMsg = "rollback selesai: v$v." },
                                onFailure = { e -> engineMsg = "rollback gagal: ${e.message ?: "?"}" },
                            )
                        } finally {
                            engineVersion = runCatching { engine.version() }.getOrNull() ?: engineVersion
                            hasBak = bakFile().isFile
                            engineBusy = false
                        }
                    }
                },
                enabled = !engineBusy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Rollback ke .bak") }
        }
    }
    if (engineMsg != null) {
        Text(engineMsg!!, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
    }
}

// ----------------------------------------------------------------------- util

private const val PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

/** Password acak 20 karakter (huruf+angka, tanpa karakter mudah tertukar). */
private fun randomPassword(): String {
    val rnd = SecureRandom()
    return StringBuilder().apply {
        repeat(20) { append(PASSWORD_ALPHABET[rnd.nextInt(PASSWORD_ALPHABET.length)]) }
    }.toString()
}

private fun appVersion(ctx: Context): String = runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
}.getOrDefault("?")

private fun formatUptime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%dj %02dm".format(h, m) else "%dm %02ds".format(m, sec)
}

/** Kolom PAT GitHub: tersimpan terenkripsi + tersinkron ke `/root/.git-credentials` distro. */
@Composable
private fun GitHubPatField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val trimmed = value.trim()
    val invalid = trimmed.any { it.isWhitespace() } // whitespace di dalam = token pasti salah
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("GitHub PAT (opsional)") },
        supportingText = {
            when {
                invalid -> Text("Token tidak valid — tidak ada spasi yang dibolehkan.", color = MaterialTheme.colorScheme.error)
                else -> Text(
                    "Buat `git clone/push/pull` di engine terautentikasi. Kosong = cabut. Butuh scope repo.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(
                onClick = { visible = !visible },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (visible) "Sembunyikan" else "Tampilkan") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
