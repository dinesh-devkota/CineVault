package com.dineshdevkota.cinevault.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dineshdevkota.cinevault.container
import com.dineshdevkota.cinevault.data.SourceEntity
import com.dineshdevkota.cinevault.smb.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NasSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCineVaultContent { NasSetupScreen(onDone = ::finish) }
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, NasSetupActivity::class.java))
    }
}

private enum class SetupStep { Devices, Connect, Shares, Folders }

@Composable
private fun NasSetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val browser = remember { NasBrowser() }
    val sourceId = remember { UUID.randomUUID().toString() }
    val listState = rememberLazyListState()
    var step by remember { mutableStateOf(SetupStep.Devices) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("445") }
    var username by remember { mutableStateOf("") }
    // Password deliberately lives only in memory until the source is saved.
    var password by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var rootPath by remember { mutableStateOf("") }
    var discovery by remember { mutableStateOf(NasDiscoveryState(searching = true)) }
    var refresh by remember { mutableIntStateOf(0) }
    var shares by remember { mutableStateOf(emptyList<NasShare>()) }
    var folders by remember { mutableStateOf(emptyList<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var credentials by remember { mutableStateOf(false) }
    var manualShare by remember { mutableStateOf(false) }
    var folderReady by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var requestJob by remember { mutableStateOf<Job?>(null) }

    fun invalidate() {
        generation++
        requestJob?.cancel()
        busy = false
        error = null
        shares = emptyList()
        folders = emptyList()
        folderReady = false
    }

    fun config(requireShare: Boolean = false): SourceConfig? {
        val parsedPort = port.trim().toIntOrNull()
        error = when {
            host.isBlank() -> "Enter a NAS address or select a device."
            port.isNotBlank() && (parsedPort == null || parsedPort !in 1..65535) -> "Enter a port between 1 and 65535."
            requireShare && share.isBlank() -> "Enter a shared folder name."
            rootPath.replace('\\', '/').split('/').any { it == ".." } -> "Choose a folder within this share."
            else -> null
        }
        if (error != null) return null
        return SourceConfig(sourceId, SmbService.normalizedHost(host), parsedPort, share.trim(),
            rootPath.replace('\\', '/').trim('/'), username.trim(), password)
    }

    fun request(action: suspend (Int) -> Unit) {
        requestJob?.cancel()
        val token = ++generation
        busy = true
        error = null
        requestJob = scope.launch {
            try {
                action(token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == token) {
                    error = SmbService.describe(failure)
                    credentials = true
                    manualShare = true
                    step = SetupStep.Connect
                }
            } finally {
                if (generation == token) busy = false
            }
        }
    }

    fun loadShares() {
        val source = config() ?: return
        invalidate()
        share = ""
        rootPath = ""
        request { token ->
            val result = browser.listShares(source)
            if (generation == token) {
                shares = result
                step = SetupStep.Shares
                manualShare = result.isEmpty()
            }
        }
    }

    fun browse(selectedShare: String, path: String) {
        share = selectedShare
        rootPath = path
        folders = emptyList()
        folderReady = false
        val source = config(requireShare = true) ?: return
        request { token ->
            val result = browser.listFolders(source)
            if (generation == token) {
                folders = result
                rootPath = source.rootPath
                folderReady = true
                step = SetupStep.Folders
            }
        }
    }

    fun back() {
        invalidate()
        step = when (step) {
            SetupStep.Devices -> { onDone(); SetupStep.Devices }
            SetupStep.Connect -> SetupStep.Devices
            SetupStep.Shares, SetupStep.Folders -> SetupStep.Connect
        }
    }

    BackHandler { back() }
    LaunchedEffect(step, rootPath) { listState.scrollToItem(0) }
    LaunchedEffect(refresh, step == SetupStep.Devices) {
        if (step == SetupStep.Devices) NasDiscovery(context).discover().collect { discovery = it }
    }
    DisposableEffect(Unit) {
        onDispose { generation++; requestJob?.cancel() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader("Add NAS", kicker = "YOUR NETWORK", trailing = when (step) {
                SetupStep.Devices -> "1 · Choose a device"
                SetupStep.Connect -> "2 · Connect"
                SetupStep.Shares -> "3 · Choose a share"
                SetupStep.Folders -> "4 · Choose a movie folder"
            })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { back() }) { Text(if (busy) "Cancel connection" else "Back") }
                if (busy) Text("Connecting…", color = SecondaryText)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        when (step) {
            SetupStep.Devices -> {
                item {
                    Text(if (discovery.searching) "Looking for NAS devices…" else "Devices on your network")
                    discovery.error?.let { Text(it, color = SecondaryText) }
                    if (!discovery.searching && discovery.devices.isEmpty()) {
                        Text("No devices advertised. Some networks and emulators require entering the address.", color = SecondaryText)
                    }
                }
                items(discovery.devices, key = { "${it.host}:${it.port}" }) { device ->
                    Button(onClick = {
                        invalidate()
                        name = device.name; host = device.host; port = device.port.toString()
                        username = ""; password = ""; credentials = false; manualShare = false
                        step = SetupStep.Connect
                        loadShares()
                    }) { Text("${device.name} · ${device.host}") }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { step = SetupStep.Connect }) { Text("Enter NAS address") }
                        OutlinedButton(onClick = { refresh++ }) { Text("Search again") }
                    }
                }
            }
            SetupStep.Connect -> {
                item {
                    TvTextField(host, { invalidate(); host = it; username = ""; password = ""; share = ""; rootPath = "" },
                        "NAS address", Modifier.fillMaxWidth(), enabled = !busy)
                }
                item {
                    TvTextField(port, { invalidate(); port = it }, "Port", keyboardType = KeyboardType.Number, enabled = !busy)
                }
                if (credentials) {
                    item { Text("Sign in with your NAS account. Leave both fields blank for guest access.", color = SecondaryText) }
                    item { TvTextField(username, { invalidate(); username = it }, "Username", Modifier.fillMaxWidth(), enabled = !busy) }
                    item { TvTextField(password, { invalidate(); password = it }, "Password", Modifier.fillMaxWidth(), isPassword = true, enabled = !busy) }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { loadShares() }, enabled = !busy) { Text("Find shared folders") }
                        OutlinedButton(onClick = { credentials = !credentials }, enabled = !busy) { Text("Sign in") }
                        OutlinedButton(onClick = { manualShare = !manualShare }, enabled = !busy) { Text("Enter share") }
                    }
                }
                if (manualShare) {
                    item { Text("If your NAS restricts browsing, enter a share you can access.", color = SecondaryText) }
                    item { TvTextField(share, { invalidate(); share = it; rootPath = "" }, "Share name", enabled = !busy) }
                    item { Button(onClick = { browse(share, "") }, enabled = !busy) { Text("Open share") } }
                }
            }
            SetupStep.Shares -> {
                item { Text("Shared folders on $host", style = MaterialTheme.typography.titleMedium) }
                if (shares.isEmpty()) item { Text("No shares returned. You can enter a share name manually.", color = SecondaryText) }
                items(shares, key = { it.name }) { entry ->
                    Button(onClick = { browse(entry.name, "") }, enabled = !busy) {
                        Column { Text(entry.name); if (entry.comment.isNotBlank()) Text(entry.comment, color = SecondaryText) }
                    }
                }
                item { OutlinedButton(onClick = { manualShare = true; step = SetupStep.Connect }, enabled = !busy) { Text("Enter share manually") } }
            }
            SetupStep.Folders -> {
                item {
                    Text("$share / ${rootPath.ifBlank { "Top level" }}", style = MaterialTheme.typography.titleMedium)
                    Text("Choose the folder containing your movies. Its subfolders will also be scanned.", color = SecondaryText)
                    TvTextField(name, { name = it }, "Library name (optional)", enabled = !busy)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = !busy && folderReady, onClick = {
                            val source = config(requireShare = true) ?: return@Button
                            val savedName = name.trim().ifBlank { source.host }
                            request { token ->
                                browser.listFolders(source)
                                if (generation != token) return@request
                                val encrypted = withContext(Dispatchers.Default) { container.secrets.encrypt(source.password) }
                                if (generation != token) return@request
                                container.database.sources().upsert(SourceEntity(
                                    id = sourceId, name = savedName, host = source.host, port = source.port,
                                    share = source.share, rootPath = source.rootPath, username = source.username,
                                    encryptedPassword = encrypted,
                                ))
                                if (generation == token) {
                                    container.scope.launch { container.scanner.scan(sourceId) }
                                    onDone()
                                }
                            }
                        }) { Text("Use this folder & scan") }
                        OutlinedButton(onClick = { browse(share, rootPath.substringBeforeLast('/', "")) }, enabled = !busy && rootPath.isNotBlank()) { Text("Up") }
                    }
                }
                items(folders, key = { it }) { folder ->
                    OutlinedButton(onClick = { browse(share, listOf(rootPath, folder).filter(String::isNotBlank).joinToString("/")) }, enabled = !busy) { Text(folder) }
                }
                if (folderReady && folders.isEmpty()) item { Text("No subfolders. You can use this folder.", color = SecondaryText) }
            }
        }
    }
}
