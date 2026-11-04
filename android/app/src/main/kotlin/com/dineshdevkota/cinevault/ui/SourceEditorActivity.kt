@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.dineshdevkota.cinevault.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.dineshdevkota.cinevault.container
import com.dineshdevkota.cinevault.data.SourceEntity
import com.dineshdevkota.cinevault.smb.SmbService
import com.dineshdevkota.cinevault.smb.SourceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class SourceEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID)
        setCineVaultContent { SourceEditorScreen(sourceId, onClose = ::finish) }
    }

    companion object {
        private const val EXTRA_SOURCE_ID = "source_id"

        fun start(context: Context, sourceId: String?) {
            context.startActivity(Intent(context, SourceEditorActivity::class.java).putExtra(EXTRA_SOURCE_ID, sourceId))
        }
    }
}

@Composable
private fun SourceEditorScreen(sourceId: String?, onClose: () -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val scanning by container.scanner.isScanning.collectAsStateWithLifecycle()
    val draftId = remember { UUID.randomUUID().toString() }
    var existing by remember { mutableStateOf<SourceEntity?>(null) }
    var loaded by remember { mutableStateOf(sourceId == null) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var rootPath by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sourceId) {
        if (sourceId == null || loaded) return@LaunchedEffect
        val source = container.database.sources().get(sourceId)
        if (source == null) {
            onClose()
            return@LaunchedEffect
        }
        existing = source
        name = source.name
        host = source.host
        port = source.port?.toString().orEmpty()
        share = source.share
        rootPath = source.rootPath
        username = source.username
        try {
            password = withContext(Dispatchers.IO) { container.secrets.decrypt(source.encryptedPassword) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "Could not read the saved password. Enter it again."
        }
        loaded = true
    }
    if (!loaded) return
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstField.requestFocus() }

    fun config(): SourceConfig? {
        val portText = port.trim()
        val portValue = portText.toIntOrNull()?.takeIf { it in 1..65535 }
        if (portText.isNotEmpty() && portValue == null) {
            message = "Port must be a number from 1 to 65535."
            return null
        }
        val hostValue = host.trim()
        val shareValue = share.trim().trim('/', '\\')
        if (hostValue.isEmpty() || shareValue.isEmpty()) {
            message = "Enter the NAS address and share name."
            return null
        }
        return SourceConfig(
            id = existing?.id ?: draftId,
            host = hostValue,
            port = portValue,
            share = shareValue,
            rootPath = rootPath.trim().trim('/', '\\', ' '),
            username = username.trim(),
            password = password,
        )
    }

    fun launchWork(work: suspend () -> Unit) {
        busy = true
        message = null
        scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Failed: ${SmbService.describe(e)}"
            } finally {
                busy = false
            }
        }
    }

    val idle = !busy && !scanning
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(if (sourceId == null) "Add NAS" else "Edit Source", kicker = "SOURCES")
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Server", style = MaterialTheme.typography.titleMedium)
                TvTextField(
                    name,
                    { name = it },
                    "Name (e.g. Living Room NAS)",
                    Modifier.fillMaxWidth().focusRequester(firstField),
                )
                TvTextField(
                    host,
                    { host = it },
                    "Host or IP (e.g. 192.168.1.20)",
                    Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Uri,
                )
                TvTextField(
                    port,
                    { port = it },
                    "Port (optional, default 445)",
                    Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Number,
                )
                TvTextField(username, { username = it }, "Username (empty for guest)", Modifier.fillMaxWidth())
                TvTextField(password, { password = it }, "Password", Modifier.fillMaxWidth(), isPassword = true)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Folder", style = MaterialTheme.typography.titleMedium)
                TvTextField(share, { share = it }, "Share (e.g. media)", Modifier.fillMaxWidth())
                TvTextField(
                    rootPath,
                    { rootPath = it },
                    "Folder inside share (e.g. Movies)",
                    Modifier.fillMaxWidth(),
                    imeAction = ImeAction.Done,
                )
                Text(
                    "Use a NAS account with read access. The password is encrypted on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(
                enabled = idle,
                onClick = {
                    val c = config() ?: return@OutlinedButton
                    launchWork {
                        container.smb.testConnection(c)
                        message = "Connected successfully."
                    }
                },
            ) { Text("Test Connection") }
            Button(
                enabled = idle,
                onClick = {
                    val c = config() ?: return@Button
                    launchWork {
                        val sealed = if (c.username.isEmpty()) "" else withContext(Dispatchers.Default) {
                            container.secrets.encrypt(c.password)
                        }
                        val entity = SourceEntity(
                            id = c.id,
                            name = name.trim().ifEmpty { c.host },
                            host = c.host,
                            port = c.port,
                            share = c.share,
                            rootPath = c.rootPath,
                            username = c.username,
                            encryptedPassword = sealed,
                            lastScanAt = existing?.lastScanAt,
                        )
                        container.database.sources().upsert(entity)
                        container.smb.reset(entity.id)
                        container.scope.launch { container.scanner.scan(entity.id) }
                        onClose()
                    }
                },
            ) { Text("Save & Scan") }
            existing?.let { source ->
                OutlinedButton(
                    enabled = idle,
                    onClick = {
                        launchWork {
                            container.database.withTransaction {
                                container.database.movies().deleteForSource(source.id)
                                container.database.sources().delete(source.id)
                            }
                            container.smb.reset(source.id)
                            onClose()
                        }
                    },
                ) { Text("Delete Source") }
            }
        }
        val note = message ?: if (scanning) "Wait for the current scan to finish." else null
        if (note != null) Text(note, color = SecondaryText)
    }
}
