package com.heddrich.companion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.heddrich.companion.inbox.InfoSection
import com.heddrich.companion.inbox.InboxRoute
import com.heddrich.companion.settings.SettingsRoute
import com.heddrich.companion.settings.SettingsStore
import com.heddrich.companion.ui.CompanionTheme
import com.heddrich.companion.ui.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings = remember { SettingsStore.Holder.get(applicationContext) }
            var appTheme by remember { mutableStateOf(ThemeMode.fromString(settings.themeMode)) }

            // Android 13+: Benachrichtigungsrecht beim ersten Start erfragen
            if (Build.VERSION.SDK_INT >= 33) {
                val notifLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                LaunchedEffect(Unit) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            CompanionTheme(appTheme) {
                MainScaffold(
                    versionLine = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    onThemeChanged = { mode ->
                        appTheme = mode
                        settings.themeMode = mode.name
                    }
                )
            }
        }
    }
}

@Composable
fun MainScaffold(
    versionLine: String,
    onThemeChanged: (ThemeMode) -> Unit
) {
    var tab by remember { mutableStateOf(0) }

    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(
                selected = tab == 0,
                onClick = { tab = 0 },
                icon = { Icon(Icons.Default.Inbox, contentDescription = null) },
                label = { Text("Inbox") }
            )
            NavigationBarItem(
                selected = tab == 1,
                onClick = { tab = 1 },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text("Einstellungen") }
            )
            NavigationBarItem(
                selected = tab == 2,
                onClick = { tab = 2 },
                icon = { Icon(Icons.Default.Info, contentDescription = null) },
                label = { Text("Info") }
            )
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (tab == 0) {
                InboxRoute()
            } else if (tab == 1) {
                SettingsRoute(onThemeChanged = onThemeChanged)
            } else {
                InfoSection(versionLine)
            }
        }
    }
}