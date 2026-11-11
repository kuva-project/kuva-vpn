package com.kuva.vpn.ui.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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

    // bottom nav locked LTR: tab order identical in fa/en, only labels translate
    Scaffold(
        bottomBar = {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides
                    androidx.compose.ui.unit.LayoutDirection.Ltr,
            ) {
            NavigationBar {
                val backStack by navController.currentBackStackEntryAsState()
                val current = backStack?.destination
                tabs.forEach { tab ->
                    val selected = current?.hierarchy?.any { it.route == tab.route } == true
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
                                    modifier = Modifier.size(if (selected) 72.dp else 52.dp),
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
                                            .background(androidx.compose.ui.graphics.Color(0xFFFF3B30)),
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
            modifier = Modifier.padding(padding),
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
