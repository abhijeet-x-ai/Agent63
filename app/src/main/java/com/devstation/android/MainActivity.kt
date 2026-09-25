package com.devstation.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.devstation.android.core.diagnostics.StartupDiagnostics
import com.devstation.android.core.diagnostics.Subsystem
import com.devstation.android.core.diagnostics.SubsystemState
import com.devstation.android.core.di.AppContainer
import com.devstation.android.core.di.DefaultAppContainer
import com.devstation.android.core.model.AppSettings
import com.devstation.android.core.ui.components.DevStationResponsiveScaffold
import com.devstation.android.core.ui.theme.DevStationTheme
import com.devstation.android.navigation.DevStationNavGraph
import kotlinx.coroutines.flow.flowOf

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (permission, isGranted) ->
            val state = if (isGranted) SubsystemState.READY else SubsystemState.DEGRADED
            StartupDiagnostics.record(
                Subsystem.SECURITY,
                state,
                "Permission $permission: ${if (isGranted) "GRANTED" else "DENIED"}"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { enableEdgeToEdge() }

        // Check and prompt for system runtime permissions
        requestRequiredPermissions()

        val crashError = intent?.getStringExtra("CRASH_ERROR")
        if (crashError != null) {
            renderRecoveryUi(crashError)
            return
        }

        val app = application as? DevStationApp
        val container: AppContainer = app?.safeContainer ?: DefaultAppContainer(applicationContext)

        setContent {
            // v1.1.3: warm Room off the main thread so first frame never blocks on
            // migrations. UI shows a loading screen until the DB is open.
            var isDbReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        (container as? DefaultAppContainer)?.prewarmDatabase()
                            ?: runCatching { container.database.openHelper.writableDatabase }
                    }
                }
                isDbReady = true
            }
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
                    if (!isDbReady) {
                        StartupLoadingScreen()
                    } else {
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

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            runCatching {
                requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
            }
        }
    }

    private fun renderRecoveryUi(errorDetails: String) {
        setContent {
            DevStationTheme(appTheme = com.devstation.android.core.model.AppTheme.DARK) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RecoveryScreen(
                        errorMessage = errorDetails,
                        onRestart = { restartApp(clearError = true) },
                        onResetDatabase = { resetDatabaseAndRestart() },
                        onOpenSettings = { openAppSettings() }
                    )
                }
            }
        }
    }

    private fun restartApp(clearError: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (!clearError) {
                putExtra("RESTART", true)
            }
        }
        startActivity(intent)
        finish()
    }

    private fun resetDatabaseAndRestart() {
        runCatching {
            // v1.1.3: clear Room singleton first, otherwise restart reuses a
            // closed/deleted handle and crashes a second time in the same process.
            com.devstation.android.core.database.DevStationDatabase.clearInstance()
            deleteDatabase("devstation_db")
        }
        restartApp(clearError = true)
    }

    private fun openAppSettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }
}

@Composable
fun StartupLoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(color = Color(0xFF38BDF8))
            Text(
                text = "Starting Agent 63…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = "Opening workspace database",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
fun RecoveryScreen(
    errorMessage: String,
    onRestart: () -> Unit,
    onResetDatabase: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFF59E0B),
                modifier = Modifier.size(56.dp)
            )

            Text(
                text = "Agent 63 System Recovery",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = "The application intercepted an unexpected startup failure. Your project files and settings are safe.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF94A3B8)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Diagnostics & Stack Trace",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Color(0xFFE2E8F0)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorMessage.take(1200),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFFCBD5E1),
                        lineHeight = 15.sp
                    )
                }
            }

            Button(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Restart Agent 63")
            }

            OutlinedButton(
                onClick = onResetDatabase,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
            ) {
                Text("Reset Database & Restart Fresh")
            }

            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF38BDF8))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open App System Permissions", color = Color(0xFF38BDF8))
            }
        }
    }
}
