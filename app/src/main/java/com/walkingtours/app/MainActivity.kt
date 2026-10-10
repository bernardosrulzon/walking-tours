package com.walkingtours.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingtours.app.ui.nav.WalkingToursNavHost
import com.walkingtours.app.ui.theme.WalkingToursTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // This launcher asks for notifications at startup and location on tour start. Only a location
        // grant matters here: a tour begun before the user answered could not start its foreground
        // service, so retry now while the app is in front of the user.
        if (grants.keys.any {
            it == Manifest.permission.ACCESS_FINE_LOCATION ||
                it == Manifest.permission.ACCESS_COARSE_LOCATION
        }
        ) {
            // The location tracker reports a refusal to the UI itself if permission is missing.
            ServiceLocator.session.onLocationPermissionChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        setContent {
            val aiState by ServiceLocator.aiSettings.state.collectAsStateWithLifecycle()
            val darkBars = when (aiState.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            // Edge-to-edge draws the app behind the status bar, so the system icons need to
            // match the theme: dark icons on light backgrounds, light icons on dark ones.
            // Without this the controller keeps light icons over our light top bars.
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).let { controller ->
                    controller.isAppearanceLightStatusBars = !darkBars
                    controller.isAppearanceLightNavigationBars = !darkBars
                }
            }
            WalkingToursTheme(themeMode = aiState.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    WalkingToursNavHost(
                        onRequestLocationPermission = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    /**
     * Android 13+ requires runtime permission for notifications. The tour notification is how the
     * user knows location is still being tracked, so ask for it up front.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    override fun onDestroy() {
        // isFinishing is true when the user deliberately leaves the app (back press, or clearing it
        // from Recents) and false for a configuration change such as a rotation. Narration is
        // spoken by a separate system process, so it has to be stopped explicitly or it keeps
        // talking after the UI is gone.
        if (isFinishing) {
            ServiceLocator.session.shutdown()
        }
        super.onDestroy()
    }
}
