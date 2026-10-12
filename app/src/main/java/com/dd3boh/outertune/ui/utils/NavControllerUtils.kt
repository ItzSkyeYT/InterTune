package com.dd3boh.outertune.ui.utils

import androidx.navigation.NavController
import com.dd3boh.outertune.ui.screens.Screens

val NavController.canNavigateUp: Boolean
    get() = currentBackStackEntry?.destination?.parent?.route != null

/**
 * Back to the tab this screen was reached from: Home, Songs, Folders, Library or whichever tabs
 * are shown. What a long press on a back button does.
 *
 * It used to navigate up while [canNavigateUp] held, and this app's NavHost is one flat graph
 * with no route, so that never held: it went up exactly once, and holding back did what tapping
 * it did. Stops at the first screen of the stack if no tab is on it, rather than emptying it,
 * and at setup: see [longBackStopsAt].
 */
fun NavController.backToMain() {
    val tabs = Screens.getAllScreens().mapTo(HashSet()) { it.route }
    do {
        if (previousBackStackEntry == null) break
        popBackStack()
    } while (!longBackStopsAt(currentBackStackEntry?.destination?.route, tabs))
}

/** Where setup is in the nav graph. */
internal const val SETUP_ROUTE = "setup_wizard"

/**
 * Whether a long press on Back has arrived: at a tab, or at setup. Setup sits over a tab, and a
 * long press from a page opened in it (the sign-in page) went on past it to that tab, which
 * has no bar and no player until setup is finished, and nothing to finish it by.
 */
internal fun longBackStopsAt(route: String?, tabs: Set<String>) = route in tabs || route == SETUP_ROUTE
