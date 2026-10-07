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
import com.walkingtours.app.tour.TourEntry
import com.walkingtours.app.ui.cities.CitiesScreen
import com.walkingtours.app.ui.settings.SettingsScreen
import com.walkingtours.app.ui.stop.StopScreen
import com.walkingtours.app.ui.tourdetail.TourDetailScreen
import com.walkingtours.app.ui.tourlist.TourListScreen

object Routes {
    const val TOURS = "tours"
    const val CITIES = "cities"
    const val CITY_TOURS = "city/{city}/tours"

    fun cityTours(city: String) = "city/${android.net.Uri.encode(city)}/tours"
    const val SETTINGS = "settings"
    const val TOUR_DETAIL = "tour/{tourId}"

    /**
     * The active-tour screen: one route for one screen, whatever brought the walker here.
     *
     * `entry` is the only argument, because which page to land on is the only thing that differs
     * between Start tour, Resume tour and tapping a stop. A typed [TourEntry] carries that intent,
     * rather than a stop id and a sentinel that each screen had to interpret for itself.
     */
    const val STOP = "tour/{tourId}/stop?entry={entry}"

    fun tourDetail(tourId: String) = "tour/$tourId"

    fun tourEntry(tourId: String, entry: TourEntry) = "tour/$tourId/stop?entry=${entry.encode()}"
}

@Composable
fun WalkingToursNavHost(onRequestLocationPermission: () -> Unit) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.CITIES,
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
        // A predictive back is not the same code path as an ordinary pop, and it has its own
        // defaults. Navigation Compose answers a predictive pop by scaling the outgoing screen to
        // 70% and merely fading the destination in, so pressing the system back button shrank the
        // page into the middle of the screen while the toolbar arrow and a quick back did the
        // slide above. Reach for predictive back here too, or back looks different depending on
        // how it was asked for.
        predictivePopEnterTransition = {
            slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 3 }
        },
        predictivePopExitTransition = {
            slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it }
        },
    ) {

        composable(Routes.CITIES) {
            CitiesScreen(
                onOpenCity = { city -> navController.navigate(Routes.cityTours(city)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.CITY_TOURS,
            arguments = listOf(navArgument("city") { type = NavType.StringType }),
        ) { entry ->
            val city = entry.arguments?.getString("city").orEmpty()
            TourListScreen(
                onOpenTour = { tourId -> navController.navigate(Routes.tourDetail(tourId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                city = city,
                onBack = { navController.popBackStack() },
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
                // Every button and every stop row leads to the same screen; the entry says where on
                // it to land. Starting a walk needs location, so permission is asked here, at the
                // moment the walker actually sets off, rather than at first launch.
                onStartTour = {
                    onRequestLocationPermission()
                    navController.navigate(Routes.tourEntry(tourId, TourEntry.Introduction))
                },
                onResumeTour = {
                    onRequestLocationPermission()
                    navController.navigate(Routes.tourEntry(tourId, TourEntry.Resume))
                },
                onStartOver = {
                    onRequestLocationPermission()
                    navController.navigate(Routes.tourEntry(tourId, TourEntry.StartOver))
                },
                onOpenStop = { stopId ->
                    onRequestLocationPermission()
                    navController.navigate(Routes.tourEntry(tourId, TourEntry.Stop(stopId)))
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.STOP,
            arguments = listOf(
                navArgument("tourId") { type = NavType.StringType },
                navArgument("entry") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { navEntry ->
            val tourId = navEntry.arguments?.getString("tourId").orEmpty()
            val tourEntry = TourEntry.decode(navEntry.arguments?.getString("entry"))
            StopScreen(
                tourId = tourId,
                entry = tourEntry,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

    }
}
