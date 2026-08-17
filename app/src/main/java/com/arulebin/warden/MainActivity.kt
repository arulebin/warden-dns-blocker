package com.arulebin.warden

import android.content.Context
import android.content.Intent
import android.Manifest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpnService()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Rules.init(this)
        Stats.init(this)
        Scheduler.apply(this)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Brand.ink.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Brand.ink.toArgb()),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            WardenTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Brand.ink) {
                    WardenApp(onArm = ::enableProtection, onDisarm = ::disableProtection)
                }
            }
        }
    }

    private fun enableProtection() {
        val consent = VpnService.prepare(this)
        if (consent != null) vpnConsentLauncher.launch(consent) else startVpnService()
    }

    private fun startVpnService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, WardenVpnService::class.java).apply { action = WardenVpnService.ACTION_CONNECT },
        )
    }

    private fun disableProtection() {
        startService(
            Intent(this, WardenVpnService::class.java).apply { action = WardenVpnService.ACTION_DISCONNECT },
        )
    }
}

@Composable
fun WardenApp(onArm: () -> Unit, onDisarm: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var showPin by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Brand.ink).systemBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ShieldScreen(onToggle = { on ->
                    when {
                        on -> onArm()
                        Prefs.lockEnabled && Prefs.hasPin() -> showPin = true
                        else -> onDisarm()
                    }
                })
                1 -> ListsScreen()
                else -> SettingsScreen()
            }
        }
        WardenBottomBar(tab) { tab = it }
    }

    if (showPin) {
        PinDialog(
            onDismiss = { showPin = false },
            onSuccess = { showPin = false; onDisarm() },
        )
    }
}

@Composable
private fun ShieldScreen(onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val on = WardenState.running
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(top = 20.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("WARDEN", style = WordmarkStyle)
            Spacer(Modifier.weight(1f))
            StatusPill(on)
        }

        if (isPrivateDnsActive(context)) {
            PrivateDnsBanner {
                runCatching { context.startActivity(Intent("android.settings.WIRELESS_SETTINGS")) }
            }
        }

        ShieldControl(on = on, onToggle = onToggle)

        Text(
            text = if (on) "PROTECTED" else "NOT PROTECTED",
            color = if (on) Brand.shield else Brand.alert,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            letterSpacing = 3.sp,
        )
        Text(
            text = if (on) "Tap the shield to stop filtering" else "Tap the shield to filter DNS across every app",
            color = Brand.textDim,
            fontSize = 13.sp,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatChip(Stats.blockedToday, "blocked today", Brand.danger, Modifier.weight(1f))
            StatChip(Stats.blockedTotal, "blocked all-time", Brand.shield, Modifier.weight(1f))
        }

        Panel {
            PanelHeader("ACTIVITY") {
                Text(
                    "Clear",
                    color = Brand.shield,
                    fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { QueryLog.clear() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (QueryLog.entries.isEmpty()) {
                EmptyHint("Turn protection on, then open an app to watch DNS decisions stream in.")
            } else {
                QueryLog.entries.forEach { ActivityRow(it) }
            }
        }
    }
}

@Composable
private fun StatusPill(on: Boolean) {
    val c = if (on) Brand.shield else Brand.alert
    Row(
        Modifier.clip(CircleShape).background(c.copy(alpha = 0.12f)).padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Text(if (on) "ARMED" else "OFF", color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    }
}

@Composable
private fun StatChip(value: Long, label: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brand.surface)
            .border(1.dp, Brand.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Mono(value.toString(), color = accent, size = 26, weight = FontWeight.Bold)
        Text(label, color = Brand.textDim, fontSize = 12.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun ActivityRow(entry: QueryLog.Entry) {
    val accent = if (entry.blocked) Brand.danger else Brand.shield
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (entry.blocked) Brand.danger else Brand.shield.copy(alpha = 0.5f)))
        Mono(entry.domain, color = if (entry.blocked) Brand.text else Brand.textDim, size = 13, modifier = Modifier.weight(1f))
        Mono(
            if (entry.blocked) "BLOCKED" else "passed",
            color = accent,
            size = 11,
            weight = if (entry.blocked) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun PrivateDnsBanner(onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brand.alert.copy(alpha = 0.12f))
            .border(1.dp, Brand.alert.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("⚠  Private DNS is on", color = Brand.alert, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            "Encrypted DNS can bypass Warden. Turn Private DNS off for full coverage.",
            color = Brand.text,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Text(
            "Open network settings →",
            color = Brand.alert,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { onOpen() }.padding(vertical = 2.dp),
        )
    }
}

@Composable
private fun WardenBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf("Shield", "Lists", "Settings")
    Column(Modifier.fillMaxWidth().background(Brand.ink)) {
        HorizontalDivider(color = Brand.line, thickness = 1.dp)
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, label ->
                val sel = i == selected
                Column(
                    modifier = Modifier.weight(1f).clickable { onSelect(i) }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        Modifier.size(width = 22.dp, height = 3.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (sel) Brand.shield else Color.Transparent),
                    )
                    Text(
                        label,
                        color = if (sel) Brand.shield else Brand.textDim,
                        fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onSuccess: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.surface,
        titleContentColor = Brand.text,
        textContentColor = Brand.textDim,
        title = { Text("Enter PIN to disarm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it; error = false },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Brand.shield,
                        unfocusedBorderColor = Brand.line,
                        focusedContainerColor = Brand.surfaceHi,
                        unfocusedContainerColor = Brand.surfaceHi,
                        cursorColor = Brand.shield,
                        focusedTextColor = Brand.text,
                        unfocusedTextColor = Brand.text,
                    ),
                )
                if (error) Text("Incorrect PIN", color = Brand.danger, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (Prefs.verifyPin(pin)) onSuccess() else error = true }) {
                Text("Confirm", color = Brand.shield)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Brand.textDim) } },
    )
}

private fun isPrivateDnsActive(context: Context): Boolean {
    val mode = Settings.Global.getString(context.contentResolver, "private_dns_mode")
    return mode != null && mode != "off"
}
