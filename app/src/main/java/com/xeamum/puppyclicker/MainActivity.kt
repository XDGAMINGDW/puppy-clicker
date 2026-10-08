package com.xeamum.puppyclicker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private var updateCheck by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        updateCheck += 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(primary = Color(0xFFE91E63), secondary = Color(0xFF8D6E63))
            ) {
                Surface(Modifier.fillMaxSize()) {
                    Surface(Modifier.safeDrawingPadding()) { App(updateCheck) }
                }
            }
        }
    }
}

@Composable
private fun App(updateCheck: Int) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var role by remember { mutableStateOf(prefs.role.takeIf { prefs.isConfigured }) }

    val logout = {
        ClickerService.stop(context)
        prefs.logout()
        ClickRepository.state.value = null
        ClickRepository.pending.value = 0
        role = null
    }

    when (role) {
        null -> SetupScreen(prefs, onConfigured = { role = it })
        Role.SENDER -> SenderScreen(prefs, logout)
        Role.RECEIVER -> ReceiverScreen(prefs, logout)
    }
    UpdateDialog(updateCheck)
}
