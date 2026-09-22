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
import androidx.compose.runtime.remember
import com.devstation.android.core.model.AppSettings
import com.devstation.android.core.ui.components.DevStationResponsiveScaffold
import com.devstation.android.core.ui.theme.DevStationTheme
import com.devstation.android.navigation.DevStationNavGraph
import kotlinx.coroutines.flow.flowOf

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { enableEdgeToEdge() }

        val app = application as DevStationApp
        val container = app.container

        setContent {
            val settingsFlow = remember {
                try {
                    container.settingsRepository.getSettings()
                } catch (_: Throwable) {
                    flowOf(AppSettings())
                }
            }
            val settings by settingsFlow.collectAsState(initial = AppSettings())

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
