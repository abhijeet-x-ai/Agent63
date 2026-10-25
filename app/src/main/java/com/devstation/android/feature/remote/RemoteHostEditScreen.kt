package com.devstation.android.feature.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.devstation.android.core.remote.SshAuthType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteHostEditScreen(
    viewModel: RemoteViewModel,
    hostId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val existing = remember(uiState.profiles, hostId) {
        if (hostId.isNullOrBlank()) null else uiState.profiles.firstOrNull { it.id == hostId }
    }

    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var host by remember(existing?.id) { mutableStateOf(existing?.host ?: "") }
    var portText by remember(existing?.id) { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember(existing?.id) { mutableStateOf(existing?.username ?: "") }
    var authType by remember(existing?.id) { mutableStateOf(existing?.authType ?: SshAuthType.PASSWORD) }
    var password by remember(existing?.id) { mutableStateOf("") }
    var privateKeyPem by remember(existing?.id) { mutableStateOf("") }
    var keyPassphrase by remember(existing?.id) { mutableStateOf("") }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    val hasSavedSecret = existing?.let { viewModel.hasSecret(it) } == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add SSH host" else "Edit SSH host") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (e.g. My VPS)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host (IP or domain)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.weight(2f)
                )
            }

            Text("Authentication", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = authType == SshAuthType.PASSWORD,
                    onClick = { authType = SshAuthType.PASSWORD },
                    label = { Text("Password") }
                )
                FilterChip(
                    selected = authType == SshAuthType.PRIVATE_KEY,
                    onClick = { authType = SshAuthType.PRIVATE_KEY },
                    label = { Text("Private key") }
                )
            }

            when (authType) {
                SshAuthType.PASSWORD -> {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(if (hasSavedSecret) "Password (blank = keep saved)" else "Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                SshAuthType.PRIVATE_KEY -> {
                    OutlinedTextField(
                        value = privateKeyPem,
                        onValueChange = { privateKeyPem = it },
                        label = {
                            Text(if (hasSavedSecret) "Private key PEM (blank = keep saved)" else "Private key (PEM)")
                        },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = keyPassphrase,
                        onValueChange = { keyPassphrase = it },
                        label = { Text("Key passphrase (optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Text(
                "Secrets are stored only in the Android Keystore (AES-256-GCM) — never in the database.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.saveHost(
                        HostFormInput(
                            id = existing?.id,
                            name = name,
                            host = host,
                            port = portText.toIntOrNull() ?: -1,
                            username = username,
                            authType = authType,
                            password = password,
                            privateKeyPem = privateKeyPem,
                            keyPassphrase = keyPassphrase
                        ),
                        onSaved = onSaved
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save host")
            }
        }
    }
}
