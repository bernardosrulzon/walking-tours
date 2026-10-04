package com.walkingtours.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.walkingtours.app.ServiceLocator
import com.walkingtours.app.ui.settings.SettingsScreen
import com.walkingtours.app.ui.stop.StopScreen
import com.walkingtours.app.ui.tourdetail.TourDetailScreen
import com.walkingtours.app.ui.tourlist.TourListScreen

object Routes {
    const val TOURS = "tours"
    const val SETTINGS = "settings"
    const val TOUR_DETAIL = "tour/{tourId}"

    /**
     * One route for one screen. Leaving [stopId] off means "resume the tour"; passing it opens that
     * stop. There used to be a separate walking screen and stop screen, which were close enough in
     * content to read as duplicates.
     */
    const val STOP = "tour/{tourId}/stop?stopId={stopId}&startAt={startAt}"

    fun tourDetail(tourId: String) = "tour/$tourId"

    fun stop(tourId: String, stopId: String) = "tour/$tourId/stop?stopId=$stopId"

    fun resumeTouring(tourId: String, startAt: String? = null): String =
        if (startAt.isNullOrBlank()) "tour/$tourId/stop" else "tour/$tourId/stop?startAt=$startAt"
}

@Composable
fun WalkingToursNavHost(onRequestLocationPermission: () -> Unit) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.TOURS,
        // A push should look like a push: the incoming screen travels a full width while the
        // outgoing one parallaxes a third of the way out, and going back is the exact reverse.
        //
        // The previous version moved each screen only a fifth of a width and leaned on a fade, so
        // screens appeared to dissolve in place instead of moving. That is what made pressing back
        // feel wrong: nothing indicated which way you had gone.
        enterTransition = {
            slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it }
        },
        exitTransition = {
            slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 3 }
        },
        popEnterTransition = {
            slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 3 }
        },
        popExitTransition = {
            slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it }
        },
    ) {

        composable(Routes.TOURS) {
            TourListScreen(
                onOpenTour = { tourId -> navController.navigate(Routes.tourDetail(tourId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.TOUR_DETAIL,
            arguments = listOf(navArgument("tourId") { type = NavType.StringType }),
        ) { entry ->
            val tourId = entry.arguments?.getString("tourId").orEmpty()
            TourDetailScreen(
                tourId = tourId,
                onBack = { navController.popBackStack() },
                onStartTour = {
                    // Geofencing needs location, so ask at the moment the user actually starts
                    // walking rather than at first launch.
                    onRequestLocationPermission()
                    navController.navigate(Routes.resumeTouring(tourId))
                },
                onOpenStop = { stopId -> navController.navigate(Routes.stop(tourId, stopId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.STOP,
            arguments = listOf(
                navArgument("tourId") { type = NavType.StringType },
                navArgument("stopId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("startAt") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val tourId = entry.arguments?.getString("tourId").orEmpty()
            val stopId = entry.arguments?.getString("stopId")
            val startAt = entry.arguments?.getString("startAt")
            StopScreen(
                tourId = tourId,
                stopId = stopId,
                startAtStopId = startAt,
                onBack = { navController.popBackStack() },
                onOpenStop = { next ->
                    // Stepping between stops REPLACES the stop page instead of stacking another one.
                    // Without this, swiping through six stops left six entries behind and the back
                    // button replayed the whole journey one stop at a time rather than returning to
                    // the tour — which is exactly what felt wrong.
                    val replacingAStop = navController.currentDestination?.route == Routes.STOP
                    navController.navigate(Routes.stop(tourId, next)) {
                        if (replacingAStop) popUpTo(Routes.STOP) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onStartTour = { fromStopId ->
                    onRequestLocationPermission()
                    // The walker is already on this stop's page, so the tour starts here instead of
                    // pushing an identical screen on top of the one they are looking at.
                    ServiceLocator.session.startTour(tourId, fromStopId)
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

    }
}
