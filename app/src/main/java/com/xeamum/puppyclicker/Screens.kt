package com.xeamum.puppyclicker

import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PartnerPaw()
            ConnectionHeart()
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onLogout) { Text("Log out") }
        }
        RelationshipCounter()
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
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
private fun PartnerPaw() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val presence by ClickRepository.partnerPresence.collectAsState()
    val connected by ClickRepository.connected.collectAsState()
    var showDetails by remember { mutableStateOf(false) }
    var shareScreen by remember { mutableStateOf(prefs.shareScreenStatus) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }
    val partner = presence
    val age = partner?.let { it.ageAtReceipt + (now - it.receivedAt).coerceAtLeast(0L) } ?: 0L
    val confirmed = connected && partner != null
    val online = confirmed && partner?.online == true && age < 65_000L
    val label = when {
        !confirmed -> "Unknown"
        online && partner?.screenOn == true -> "Screen on"
        online -> "Online"
        partner?.lastSeen == 0L -> "Not seen"
        partner?.online == true -> "Stale"
        age < 60_000L -> "${age / 1_000L}s ago"
        age < 3_600_000L -> "${age / 60_000L}m ago"
        age < 86_400_000L -> "${age / 3_600_000L}h ago"
        else -> "${age / 86_400_000L}d ago"
    }
    Column(Modifier.width(76.dp).height(68.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = { showDetails = true }) {
            Icon(
                painterResource(R.drawable.ic_paw),
                contentDescription = "Partner status: $label. View details",
                modifier = Modifier.size(24.dp),
                tint = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }

    if (showDetails) {
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Partner status") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (online) "App connected" else if (!confirmed || partner?.online == true) "Connection unconfirmed" else "App offline")
                    Text("Last seen: ${partner?.lastSeen?.takeIf { it > 0 }?.let { formatter.format(Instant.ofEpochMilli(it)) } ?: "Unknown"}")
                    Text(when {
                        !online -> "Current screen status unknown"
                        partner?.screenOn == true -> "Screen on"
                        partner?.screenOn == false -> "Screen off"
                        else -> "Screen status not shared"
                    })
                    Text("Last screen on: ${partner?.lastScreenOn?.takeIf { it > 0 }?.let { formatter.format(Instant.ofEpochMilli(it)) } ?: "Unknown or not shared"}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Share this phone's screen status", modifier = Modifier.weight(1f))
                        Switch(checked = shareScreen, onCheckedChange = {
                            shareScreen = it
                            prefs.shareScreenStatus = it
                        })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun RelationshipCounter() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var startDate by remember { mutableStateOf(prefs.relationshipStart) }
    var today by remember { mutableStateOf(LocalDate.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now()
            delay(60_000L)
        }
    }

    val chooseDate = {
        val now = LocalDate.now()
        val initial = (startDate ?: now).coerceAtMost(now)
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val selected = LocalDate.of(year, month + 1, day)
                if (!selected.isAfter(LocalDate.now())) {
                    prefs.relationshipStart = selected
                    startDate = selected
                    today = LocalDate.now()
                }
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    val start = startDate
    if (start == null) {
        TextButton(onClick = { chooseDate() }) {
            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Set relationship start date", modifier = Modifier.padding(start = 8.dp))
        }
    } else {
        val end = today.coerceAtLeast(start)
        val length = Period.between(start, end)
        val duration = buildList {
            if (length.years > 0) add("${length.years} ${if (length.years == 1) "year" else "years"}")
            if (length.months > 0) add("${length.months} ${if (length.months == 1) "month" else "months"}")
            if (length.days > 0 || isEmpty()) add("${length.days} ${if (length.days == 1) "day" else "days"}")
        }.joinToString(", ")
        val days = ChronoUnit.DAYS.between(start, end)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Together $duration", style = MaterialTheme.typography.titleSmall)
                Text(
                    "$days ${if (days == 1L) "day" else "days"} since ${start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = { chooseDate() }) {
                Icon(Icons.Default.DateRange, contentDescription = "Change relationship start date")
            }
        }
    }
}

@Composable
private fun ConnectionHeart() {
    val lastUpdate by ClickRepository.lastServerUpdate.collectAsState()
    val connected by ClickRepository.connected.collectAsState()
    var ageMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(lastUpdate) {
        val timestamp = lastUpdate ?: return@LaunchedEffect
        while (true) {
            ageMs = (SystemClock.elapsedRealtime() - timestamp).coerceAtLeast(0L)
            delay(if (ageMs < 1_000L) 100L else 1_000L)
        }
    }

    val age = lastUpdate?.let {
        val elapsed = (SystemClock.elapsedRealtime() - it).coerceAtLeast(ageMs)
        when {
            elapsed < 1_000L -> "$elapsed ms"
            elapsed < 60_000L -> "${elapsed / 1_000L} s"
            elapsed < 3_600_000L -> "${elapsed / 60_000L} min"
            elapsed < 86_400_000L -> "${elapsed / 3_600_000L} h"
            else -> "${elapsed / 86_400_000L} d"
        }
    } ?: "--"
    val status = when {
        lastUpdate == null -> "No server update yet"
        !connected -> "Offline; last server update $age ago"
        ageMs >= 60_000L -> "Connection stale; last server update $age ago"
        else -> "Last server update $age ago"
    }
    Column(
        modifier = Modifier.width(76.dp).height(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Favorite,
            contentDescription = status,
            modifier = Modifier.size(20.dp),
            tint = if (connected && lastUpdate != null && ageMs < 60_000L) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
        Text(age, style = MaterialTheme.typography.labelSmall, maxLines = 1)
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
