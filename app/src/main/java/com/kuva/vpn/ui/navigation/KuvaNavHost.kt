package com.kuva.vpn.ui.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kuva.vpn.R
import com.kuva.vpn.ui.dashboard.DashboardScreen
import com.kuva.vpn.ui.freecfg.FreeConfigsScreen
import com.kuva.vpn.ui.mycfg.MyConfigsScreen
import com.kuva.vpn.ui.settings.SettingsScreen

/** Gives inner screens access to the NavController for back-to-home handling. */
val LocalNavController = androidx.compose.runtime.staticCompositionLocalOf<androidx.navigation.NavHostController?> { null }

object Routes {
    const val HOME = "home"
    const val FREE = "free"
    const val MINE = "mine"
    const val SETTINGS = "settings"
}

private data class Tab(
    val route: String,
    val labelRes: Int,
    val iconRes: Int,
)

@Composable
fun KuvaNavHost(sharedLink: String? = null) {
    val navController = rememberNavController()

    // language toggle: fa <-> en via per-app locales
    val context = androidx.compose.ui.platform.LocalContext.current
    val isFa = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        .toLanguageTags().startsWith("fa")
    val toggleLanguage = {
        val target = if (isFa) "en" else "fa"
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(target)
        )
    }
    // app-start update check result (computed once in KuvaApplication):
    // drives the red dot on the settings tab until the app is updated
    val updateAvailable by (androidx.compose.ui.platform.LocalContext.current.applicationContext
        as com.kuva.vpn.KuvaApplication).updateAvailable.collectAsState()

    val tabs = listOf(
        Tab(Routes.HOME, R.string.nav_home, R.drawable.ic_nav_home),
        Tab(Routes.FREE, R.string.nav_free, R.drawable.ic_nav_free),
        Tab(Routes.MINE, R.string.nav_mine, R.drawable.ic_nav_mine),
        Tab(Routes.SETTINGS, R.string.nav_settings, R.drawable.ic_nav_settings),
    )

    // patch 257: during the cold-start transition MIUI briefly reports a
    // zero top (status-bar) inset, so the whole UI slid up ~statusbar-height
    // for a frame or two and back ("invisible space above the header
    // disappears and reappears"). Latch the largest top inset ever seen:
    // it can only grow, never shrink, so a transient zero can no longer
    // move the layout — the jump becomes structurally impossible.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusBarTop = WindowInsets.statusBars.getTop(density)
    val maxStatusBarTop = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableIntStateOf(statusBarTop)
    }
    if (statusBarTop > maxStatusBarTop.intValue) {
        maxStatusBarTop.intValue = statusBarTop
    }

    // bottom nav locked LTR: tab order identical in fa/en, only labels translate
    androidx.compose.runtime.CompositionLocalProvider(LocalNavController provides navController) {
    // patch 257: Scaffold's default contentWindowInsets would feed the
    // (possibly transient-zero) top inset straight into the body padding —
    // zero them out and apply the latched top inset ourselves below.
    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        bottomBar = {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides
                    androidx.compose.ui.unit.LayoutDirection.Ltr,
            ) {
            NavigationBar {
                val backStack by navController.currentBackStackEntryAsState()
                val current = backStack?.destination
                tabs.forEach { tab ->
                    // patch 256: on the very first frames currentBackStackEntryAsState
                    // still returns null, so NO tab was selected and every icon
                    // rendered small (52dp); a frame later Home became selected and
                    // the icons grew to 72dp — a visible flicker + layout jump.
                    // Until the back stack resolves, Home counts as selected.
                    val selected = if (current == null) tab.route == Routes.HOME
                        else current.hierarchy.any { it.route == tab.route }
                    NavigationBarItem(
                        selected = selected,
                        colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                            indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(Routes.HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            // user-designed colorful squircle icons: shown untouched.
                            // Active tab: NO halo/glow (NavigationBarItem draws none
                            // itself — the old effect was the icon's own saturation
                            // filter), just a bigger icon + fully sharp colors.
                            // Inactive: smaller + slightly dimmed.
                            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Image(
                                    painter = painterResource(tab.iconRes),
                                    contentDescription = null,
                                    // patch 256: the 72dp/52dp size swap changed the
                                    // NavigationBar height on selection, the Scaffold
                                    // padding recomputed and the whole screen jumped.
                                    // The slot is now a constant 72dp; only the visual
                                    // scale changes, so the layout can never shift.
                                    modifier = Modifier
                                        .size(72.dp)
                                        .graphicsLayer {
                                            val sc = if (selected) 1f else 52f / 72f
                                            scaleX = sc
                                            scaleY = sc
                                        },
                                    alpha = if (selected) 1f else 0.75f,
                                    colorFilter = if (selected) null else
                                        androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                                            androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0.85f) },
                                        ),
                                )
                                // red update dot on the settings tab until updated
                                if (tab.route == Routes.SETTINGS && updateAvailable) {
                                    Box(
                                        modifier = Modifier
                                            .align(androidx.compose.ui.Alignment.TopEnd)
                                            .padding(top = 2.dp, end = 2.dp)
                                            .size(13.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(com.kuva.vpn.ui.theme.StatusRed),
                                    )
                                }
                            }
                        },
                        label = {
                            // short fa label centered under the icon
                            Text(
                                androidx.compose.ui.res.stringResource(tab.labelRes),
                                maxLines = 1,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                }
            }
            }  // end LTR nav provider
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            // patch 257: top = latched status-bar inset (never shrinks);
            // bottom = Scaffold's own value (bottom bar + nav-bar inset)
            modifier = Modifier.padding(
                androidx.compose.foundation.layout.PaddingValues(
                    top = with(density) { maxStatusBarTop.intValue.toDp() },
                    // patch 260: calculateBottomPadding is a MEMBER of
                    // PaddingValues (no import needed) and already returns Dp
                    bottom = padding.calculateBottomPadding(),
                )
            ),
        ) {
            composable(Routes.HOME) { DashboardScreen(onToggleLanguage = toggleLanguage, isFa = isFa) }
            // screens locked LTR: only the texts translate, icons and their
            // order never mirror when the app language is Persian
            composable(Routes.FREE) { LtrBox { FreeConfigsScreen() } }
            composable(Routes.MINE) {
                // request #4: back on the "my configs" tab returns to the dashboard
                androidx.activity.compose.BackHandler {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                LtrBox { MyConfigsScreen(sharedLink = sharedLink) }
            }
            composable(Routes.SETTINGS) { SettingsScreen() }
        }
    }
    }  // end LocalNavController provider
}

/** Forces LTR layout for its content regardless of app locale. */
@Composable
private fun LtrBox(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides
            androidx.compose.ui.unit.LayoutDirection.Ltr,
        content = content,
    )
}
