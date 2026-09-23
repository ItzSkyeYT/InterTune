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
 * it did. Stops at the first screen of the stack if no tab is on it, rather than emptying it.
 */
fun NavController.backToMain() {
    val tabs = Screens.getAllScreens().mapTo(HashSet()) { it.route }
    do {
        if (previousBackStackEntry == null) break
        popBackStack()
    } while (currentBackStackEntry?.destination?.route !in tabs)
}
