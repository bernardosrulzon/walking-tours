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
import com.walkingtours.app.tour.TourEntry
import com.walkingtours.app.ui.cities.CitiesScreen
import com.walkingtours.app.ui.detour.DetourScreen
import com.walkingtours.app.ui.persona.PersonaScreen
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

    /**
     * The persona flow, shown before a walk begins. `entry` is where the walk joins once a guide is
     * chosen; `start` says whether finishing means "begin that walk" or just "return".
     */
    const val PERSONA = "tour/{tourId}/persona?start={start}&entry={entry}"

    /**
     * A detour deep-dive: one generated chapter outside the pager. `topic` is the topic id the
     * picker suggested; the page resolves it again, so a dead process or a changed suggestion set
     * lands on an error rather than a blank page.
     */
    const val DETOUR = "tour/{tourId}/detour?topic={topic}"

    fun tourDetail(tourId: String) = "tour/$tourId"

    fun persona(tourId: String, start: Boolean, entry: TourEntry) =
        "tour/$tourId/persona?start=$start&entry=${entry.encode()}"

    fun detour(tourId: String, topicId: String) =
        "tour/$tourId/detour?topic=${android.net.Uri.encode(topicId)}"

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
        ) { navEntry ->
            val tourId = navEntry.arguments?.getString("tourId").orEmpty()

            // Any way into a walk — Start, Resume, Start over, or a tapped stop — needs a guide the
            // first time. The entry is carried through the persona flow so the walk joins where the
            // walker meant to, not always at the introduction.
            fun enterWalk(entry: TourEntry) {
                onRequestLocationPermission()
                if (ServiceLocator.personaSettings.current.guide(tourId) == null) {
                    navController.navigate(Routes.persona(tourId, start = true, entry = entry))
                } else {
                    navController.navigate(Routes.tourEntry(tourId, entry))
                }
            }

            TourDetailScreen(
                tourId = tourId,
                onBack = { navController.popBackStack() },
                // Every button and every stop row leads to the same screen; the entry says where on
                // it to land. Starting a walk needs location, so permission is asked here, at the
                // moment the walker actually sets off, rather than at first launch.
                onStartTour = { enterWalk(TourEntry.Introduction) },
                onOpenPersona = {
                    navController.navigate(
                        Routes.persona(tourId, start = false, entry = TourEntry.Resume),
                    )
                },
                onResumeTour = { enterWalk(TourEntry.Resume) },
                onStartOver = { enterWalk(TourEntry.StartOver) },
                onOpenStop = { stopId -> enterWalk(TourEntry.Stop(stopId)) },
                onOpenDetour = { topicId -> navController.navigate(Routes.detour(tourId, topicId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.PERSONA,
            arguments = listOf(
                navArgument("tourId") { type = NavType.StringType },
                navArgument("start") {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument("entry") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { navEntry ->
            val tourId = navEntry.arguments?.getString("tourId").orEmpty()
            val start = navEntry.arguments?.getBoolean("start") ?: false
            // Where the walk joins once a guide is chosen — carried in, so Resume resumes.
            val entry = TourEntry.decode(navEntry.arguments?.getString("entry"))
            PersonaScreen(
                tourId = tourId,
                onBack = { navController.popBackStack() },
                onDone = {
                    if (start) {
                        navController.navigate(Routes.tourEntry(tourId, entry)) {
                            // The persona flow is a step in starting, not a page to go back to.
                            popUpTo(Routes.PERSONA) { inclusive = true }
                        }
                    } else {
                        // Arrived from the tour page to change the guide: leave the walker there.
                        navController.popBackStack()
                    }
                },
            )
        }

        composable(
            route = Routes.DETOUR,
            arguments = listOf(
                navArgument("tourId") { type = NavType.StringType },
                navArgument("topic") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { navEntry ->
            val tourId = navEntry.arguments?.getString("tourId").orEmpty()
            val topicId = navEntry.arguments?.getString("topic").orEmpty()
            DetourScreen(
                tourId = tourId,
                topicId = topicId,
                onBack = { navController.popBackStack() },
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
                onOpenPersona = {
                    navController.navigate(Routes.persona(tourId, start = false, entry = TourEntry.Resume))
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

    }
}
