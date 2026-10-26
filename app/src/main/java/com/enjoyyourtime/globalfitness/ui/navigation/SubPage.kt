package com.enjoyyourtime.globalfitness.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.enjoyyourtime.globalfitness.ui.theme.AppMotion

/**
 * Sub-pages slide in from the end and back out (also with the predictive back gesture). [content]
 * gets a back action that ignores repeated taps during the exit animation, and the back stack
 * entry (for route arguments).
 */
fun NavGraphBuilder.subPageWithArgs(
    route: String,
    navController: NavHostController,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (back: () -> Unit, entry: NavBackStackEntry) -> Unit,
) {
    composable(
        route,
        arguments = arguments,
        enterTransition = { slideInHorizontally(AppMotion.spatial()) { it / 3 } + fadeIn(AppMotion.effects()) },
        exitTransition = { fadeOut(AppMotion.effects()) },
        popEnterTransition = { fadeIn(AppMotion.effects()) },
        popExitTransition = { slideOutHorizontally(AppMotion.spatial()) { it / 3 } + fadeOut(AppMotion.effects()) },
    ) { entry ->
        // LocalLifecycleOwner is this back stack entry: a second tap after it started leaving is dropped.
        content(dropUnlessResumed { navController.popBackStack() }, entry)
    }
}

/** A sub-page without arguments (see [subPageWithArgs]). */
fun NavGraphBuilder.subPage(
    route: String,
    navController: NavHostController,
    content: @Composable (back: () -> Unit) -> Unit,
) = subPageWithArgs(route, navController) { back, _ -> content(back) }

/** Opens a sub-page once, even on a double tap. */
fun NavHostController.openSubPage(route: String) {
    navigate(route) { launchSingleTop = true }
}
