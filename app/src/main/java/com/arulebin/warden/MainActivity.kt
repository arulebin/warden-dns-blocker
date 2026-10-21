package com.arulebin.warden

import android.Manifest
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
        Egress.init(this)
        Scheduler.apply(this)
        Lists.refreshStale()

        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val dark = isDarkTheme()
            // Keep status/nav bar icons legible when the in-app theme differs from the system's.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            WardenTheme(dark = dark) {
                WardenApp(onArm = ::enableProtection, onDisarm = ::disableProtection)
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

private data class Destination(val label: String, val icon: ImageVector)

private val DESTINATIONS = listOf(
    Destination("Shield", WardenIcons.Shield),
    Destination("Focus", WardenIcons.Target),
    Destination("Lists", WardenIcons.Lists),
    Destination("AI", WardenIcons.Sparkle),
    Destination("Settings", WardenIcons.Sliders),
)

@Composable
fun WardenApp(onArm: () -> Unit, onDisarm: () -> Unit) {
    val c = Warden.colors
    val toaster = remember { ToastState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showPin by remember { mutableStateOf(false) }

    // Back from a secondary tab returns to the shield instead of leaving the app.
    BackHandler(enabled = tab != 0) { tab = 0 }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 108.dp
    val insets = PaddingValues(top = top, bottom = bottom)

    CompositionLocalProvider(LocalToaster provides toaster) {
        Box(Modifier.fillMaxSize().background(c.background)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    // Direction follows the tab order: moving right slides content in from the right.
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 500f)) { it / 5 * dir } +
                        fadeIn(Motion.fade(260))) togetherWith
                        (slideOutHorizontally(Motion.fade(180)) { -it / 8 * dir } + fadeOut(Motion.fade(140)))
                },
                label = "tabs",
            ) { page ->
                when (page) {
                    0 -> HomeScreen(
                        onToggle = { on ->
                            when {
                                on -> onArm()
                                Prefs.lockEnabled && Prefs.hasPin() -> showPin = true
                                else -> onDisarm()
                            }
                        },
                        insets = insets,
                    )
                    1 -> FocusScreen(insets, onArm = onArm)
                    2 -> ListsScreen(insets)
                    3 -> LedgerScreen(insets)
                    else -> SettingsScreen(insets)
                }
            }

            // Content fades out under the floating bar rather than hard-clipping against it.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bottom)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, c.background.copy(alpha = 0.92f), c.background))),
            )

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ToastHost(toaster)
                FloatingNavBar(selected = tab, onSelect = { tab = it })
            }
        }
    }

    if (showPin) {
        PinDialog(
            title = "Enter your PIN",
            subtitle = "A PIN is required to turn protection off.",
            onDismiss = { showPin = false },
            onSubmit = { pin ->
                if (Prefs.verifyPin(pin)) {
                    showPin = false
                    onDisarm()
                    null
                } else {
                    "That PIN isn't right. Try again."
                }
            },
        )
    }
}

/** A floating pill bar; the selection indicator glides between tabs on a spring. */
@Composable
private fun FloatingNavBar(selected: Int, onSelect: (Int) -> Unit) {
    val c = Warden.colors
    val shape = RoundedCornerShape(28.dp)
    BoxWithConstraints(
        Modifier
            .padding(horizontal = 20.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .shadow(if (c.isDark) 0.dp else 18.dp, shape, ambientColor = c.text.copy(alpha = 0.2f), spotColor = c.text.copy(alpha = 0.25f))
            .clip(shape)
            .background(c.surface)
            .border(1.dp, if (c.isDark) c.line else Color.Transparent, shape)
            .padding(6.dp),
    ) {
        val itemWidth = maxWidth / DESTINATIONS.size
        val indicatorX by animateDpAsState(itemWidth * selected, Motion.settle(), label = "navIndicator")
        Box(
            Modifier
                .offset(x = indicatorX)
                .width(itemWidth)
                .height(56.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(c.guardSoft),
        )
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            DESTINATIONS.forEachIndexed { i, dest ->
                NavItem(dest, selected = i == selected, onClick = { onSelect(i) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NavItem(dest: Destination, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Warden.colors
    val tint by animateColorAsState(if (selected) c.guard else c.textDim, Motion.fade(), label = "navTint")
    val labelColor by animateColorAsState(if (selected) c.text else c.textDim, Motion.fade(), label = "navLabel")
    // A small hop when an item becomes selected.
    val hop = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected) {
            hop.snapTo(0f)
            hop.animateTo(-4f, spring(dampingRatio = 0.4f, stiffness = 900f))
            hop.animateTo(0f, Motion.bouncy())
        }
    }

    Column(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(22.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            dest.icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp).graphicsLayer { translationY = hop.value * density },
            tint = tint,
        )
        Text(dest.label, style = Warden.type.caption, color = labelColor, modifier = Modifier.padding(top = 2.dp))
    }
}
