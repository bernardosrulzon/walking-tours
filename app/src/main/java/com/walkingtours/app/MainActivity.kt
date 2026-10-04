package com.walkingtours.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.walkingtours.app.ui.nav.WalkingToursNavHost
import com.walkingtours.app.ui.theme.WalkingToursTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* The location tracker reports its own state to the UI if permission is refused. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        setContent {
            WalkingToursTheme {
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
