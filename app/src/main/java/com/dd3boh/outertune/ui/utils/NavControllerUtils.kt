package com.dd3boh.outertune.ui.utils

import android.annotation.SuppressLint
import androidx.navigation.NavController
import androidx.navigation.NavGraph
import com.dd3boh.outertune.ui.screens.Screens
import com.dd3boh.outertune.ui.screens.walkthrough.Tour

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

/**
 * Whether [route] is the list of settings or one of its screens. Not "settings_tour", which is no
 * screen to stay on: it goes to the list and to the screen the closer look starts at, and pops
 * itself once it is there.
 */
internal fun isSettingsRoute(route: String?): Boolean =
    route == Tour.ROUTE_SETTINGS || route?.startsWith(Tour.ROUTE_SETTINGS + "/") == true

/**
 * How many pages of a history, [routes] from the oldest, go when Settings is left: the lowest
 * screen of Settings in it and every page above, since what was opened from there (an artist
 * from the player, the sign-in page) is not a page of the tab it was over. None if Settings is
 * not in it. Never the first page: the history is not emptied.
 */
internal fun pagesLeftWithSettings(routes: List<String?>): Int {
    val lowest = routes.indexOfFirst { isSettingsRoute(it) }
    return if (lowest < 0) 0 else routes.size - lowest.coerceAtLeast(1)
}

/**
 * Leaves Settings: pops its lowest screen in the history and what is above it ([pagesLeftWithSettings]),
 * none of them parked. Whether there was any.
 *
 * What the tab bar parks (popUpTo with saveState) comes back when its tab is tapped again, so
 * Settings that was in the history when another tab was tapped came back over the tab (#135).
 * It is left here first, the way the arrow at the top of it leaves it, and what is parked is the
 * tab's own.
 */
// The pages under the current one can only be read from the navigation library's own list, which it marks
// as for its library group; nothing public gives them. The library's version is pinned, and an update that
// took the list away would fail the build, not the app.
@SuppressLint("RestrictedApi")
fun NavController.leaveSettings(): Boolean {
    val pages = currentBackStack.value.filter { it.destination !is NavGraph }.map { it.destination.route }
    var left = 0
    repeat(pagesLeftWithSettings(pages)) { if (popBackStack()) left++ }
    return left > 0
}

/**
 * The tab that holds the page showing: the highest of [tabs] in a history, [routes] from the
 * oldest. On an artist opened from Library it is Library, and on Library itself. Null if no tab
 * is in the history.
 */
internal fun tabHolding(routes: List<String?>, tabs: Set<String>): String? = routes.lastOrNull { it in tabs }

/**
 * How many pages of a history, [routes] from the oldest, a tab holds: those above the highest of
 * [tabs] in it, an artist opened from Library and the album opened from that artist. Above the
 * first page if no tab is in it: the history is not emptied.
 */
internal fun pagesOverTab(routes: List<String?>, tabs: Set<String>): Int =
    (routes.size - 1 - routes.indexOfLast { it in tabs }.coerceAtLeast(0)).coerceAtLeast(0)

/**
 * Closes what the tab at [tab] holds, if it is the one that holds the page showing
 * ([tabHolding]): pops the pages over it ([pagesOverTab]) the way Back pops them, none of them
 * parked, down to the tab's own page where it was left. Whether there was any.
 *
 * A tap on the tab from one of its pages used to make the tab a second time and park the first
 * with its pages (popUpTo with saveState), which only hid them: at the next change of tab and
 * back they were there again (#143, "pressing the same tab should permanently close it").
 */
// The history is read as leaveSettings reads it, and after it: a pop is in the list at once.
@SuppressLint("RestrictedApi")
fun NavController.closeTabPages(tab: String, tabs: Set<String>): Boolean {
    val pages = currentBackStack.value.filter { it.destination !is NavGraph }.map { it.destination.route }
    if (tabHolding(pages, tabs) != tab) return false
    var left = 0
    repeat(pagesOverTab(pages, tabs)) { if (popBackStack()) left++ }
    return left > 0
}
