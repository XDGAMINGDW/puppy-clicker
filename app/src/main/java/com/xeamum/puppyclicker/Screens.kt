package com.xeamum.puppyclicker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

private const val MAX_CLICKS_PER_SEND = 100

@Composable
fun SetupScreen(prefs: Prefs, onConfigured: (Role) -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.serverUrl.ifEmpty { "https://" }) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(R.drawable.ic_paw), null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Puppy Clicker", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim() },
            label = { Text("Server address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.trim() },
            label = { Text("Secret code") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !busy && code.isNotEmpty(),
            onClick = {
                if (url.toHttpUrlOrNull()?.isHttps != true) {
                    error = "Address must start with https://"
                    return@Button
                }
                busy = true
                error = null
                scope.launch {
                    try {
                        val role = ApiClient(url, code).fetchRole()
                        prefs.bindCounters(code)
                        prefs.serverUrl = url
                        prefs.token = code
                        prefs.role = role
                        prefs.lastKnownTotal = -1
                        prefs.lastKnownUsed = -1
                        onConfigured(role)
                    } catch (e: Exception) {
                        error = e.message ?: "Could not connect"
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(if (busy) "Connecting..." else "Connect") }
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SenderScreen(prefs: Prefs, onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val state by ClickRepository.state.collectAsState()
    val pending by ClickRepository.pending.collectAsState()
    val connected by ClickRepository.connected.collectAsState()
    var amount by remember { mutableIntStateOf(1) }
    var message by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ClickerService.start(context)
    }
    LaunchedEffect(Unit) {
        Sync.showLocal(context)
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else ClickerService.start(context)
    }

    ScreenLayout(title = "Send clicks", onLogout = onLogout) {
        Text("He has", style = MaterialTheme.typography.titleMedium)
        BigCount(state?.available)
        Text("clicks available")
        Text(
            if (connected) "Connected" else "Offline, showing saved count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { amount = (amount - 1).coerceAtLeast(1) }) { Text("-") }
            Text("$amount", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 24.dp))
            FilledTonalButton(onClick = { amount = (amount + 1).coerceAtMost(MAX_CLICKS_PER_SEND) }) { Text("+") }
        }
        Spacer(Modifier.height(24.dp))
        PawButton(label = "Click!", enabled = true) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val sent = amount
            Sync.addClicks(context, sent)
            scope.launch {
                message = try {
                    if (Sync.pushOrQueue(context)) {
                        if (sent == 1) "Sent 1 click!" else "Sent $sent clicks!"
                    } else {
                        "No connection. Saved, will send automatically."
                    }
                } catch (e: Exception) {
                    "Saved, but the server refused it: ${e.message}"
                }
            }
        }
        message?.let { Text(it, modifier = Modifier.padding(top = 16.dp)) }
        PendingNote(pending, "waiting to send")
    }
}

@Composable
fun ReceiverScreen(prefs: Prefs, onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ClickRepository.state.collectAsState()
    val connected by ClickRepository.connected.collectAsState()
    val pending by ClickRepository.pending.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ClickerService.start(context)
    }
    LaunchedEffect(Unit) {
        Sync.showLocal(context)
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else ClickerService.start(context)
    }

    ScreenLayout(title = "Your clicks", onLogout = onLogout) {
        BigCount(state?.available)
        Text("clicks available")
        Text(
            if (connected) "Connected" else "Offline, showing saved count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(32.dp))
        PawButton(label = "Good boy!", enabled = (state?.available ?: 0) > 0) {
            if (!Sync.useClick(context)) return@PawButton
            playGoodBoy(context)
            message = "Good boy!"
            scope.launch { runCatching { Sync.pushOrQueue(context) } }
        }
        message?.let { Text(it, modifier = Modifier.padding(top = 16.dp)) }
        PendingNote(pending, "waiting to sync")
    }
}

@Composable
fun UpdateDialog(checkTrigger: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var apk by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(checkTrigger) {
        if (!downloading && apk == null) update = runCatching { Updater.check() }.getOrNull()
    }
    val info = update ?: return

    AlertDialog(
        onDismissRequest = { if (!downloading) update = null },
        title = { Text("Update available") },
        text = {
            Text(
                error ?: when {
                    apk != null -> "Version ${info.versionName} is ready to install."
                    downloading -> "Downloading... ${(progress * 100).toInt()}%"
                    else -> "Version ${info.versionName} is available (you have ${BuildConfig.VERSION_NAME})."
                }
            )
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    apk?.let {
                        Updater.install(context, it)
                        return@TextButton
                    }
                    downloading = true
                    error = null
                    scope.launch {
                        try {
                            val file = Updater.download(context, info) { progress = it }
                            apk = file
                            Updater.install(context, file)
                        } catch (e: Exception) {
                            error = "Download failed: ${e.message}"
                        } finally {
                            downloading = false
                        }
                    }
                },
            ) { Text(if (apk != null) "Install" else "Update") }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = { update = null }) { Text("Later") }
        },
    )
}

@Composable
private fun ScreenLayout(title: String, onLogout: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onLogout) { Text("Log out") }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun BigCount(value: Int?) {
    Text(
        value?.toString() ?: "-",
        style = MaterialTheme.typography.displayLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun PendingNote(pending: Int, label: String) {
    if (pending <= 0) return
    Text(
        if (pending == 1) "1 click $label" else "$pending clicks $label",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private fun playGoodBoy(context: Context) {
    val player = MediaPlayer.create(context, R.raw.good_boy) ?: return
    player.setOnCompletionListener { it.release() }
    player.start()
}

@Composable
private fun PawButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = Modifier.size(180.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_paw), null, Modifier.size(72.dp))
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
}
