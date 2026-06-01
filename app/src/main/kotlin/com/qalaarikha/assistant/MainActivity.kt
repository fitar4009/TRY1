package com.qalaarikha.assistant

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import androidx.compose.runtime.LaunchedEffect
import com.qalaarikha.assistant.ui.nav.AppNavGraph
import com.qalaarikha.assistant.ui.theme.QalaArikhaTheme

/**
 * Single activity that hosts the Compose UI.
 * RTL layout is enforced globally via [LocalLayoutDirection].
 */
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            QalaArikhaTheme {
                // Force RTL for all Hebrew content
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(modifier = Modifier.fillMaxSize()) {

                        // Request RECORD_AUDIO up-front; other permissions are requested
                        // contextually when the relevant action is first attempted.
                        val audioPermission = rememberMultiplePermissionsState(
                            permissions = listOf(Manifest.permission.RECORD_AUDIO)
                        )
                        LaunchedEffect(Unit) {
                            if (!audioPermission.allPermissionsGranted) {
                                audioPermission.launchMultiplePermissionRequest()
                            }
                        }

                        AppNavGraph()
                    }
                }
            }
        }
    }
}
