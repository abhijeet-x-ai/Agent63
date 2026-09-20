package com.devstation.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.devstation.android.core.model.AppSettings
import com.devstation.android.core.ui.components.DevStationResponsiveScaffold
import com.devstation.android.core.ui.theme.DevStationTheme
import com.devstation.android.navigation.DevStationNavGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as DevStationApp
        val container = app.container

        setContent {
            val settings by container.settingsRepository.getSettings().collectAsState(initial = AppSettings())

            DevStationTheme(appTheme = settings.theme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    DevStationResponsiveScaffold(
                        navController = navController,
                        activeProjectName = null
                    ) {
                        DevStationNavGraph(
                            navController = navController,
                            container = container
                        )
                    }
                }
            }
        }
    }
}
