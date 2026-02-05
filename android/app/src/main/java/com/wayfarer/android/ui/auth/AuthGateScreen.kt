package com.wayfarer.android.ui.auth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wayfarer.android.R
import com.wayfarer.android.api.ServerConfigStore
import com.wayfarer.android.sync.WayfarerSyncManager
import com.wayfarer.android.sync.WayfarerSyncScheduler
import com.wayfarer.android.ui.UiError
import com.wayfarer.android.ui.UiErrorFormatter

@Composable
fun AuthGateScreen(
    onLoginSuccess: () -> Unit,
    onContinueOffline: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val syncManager = remember { WayfarerSyncManager(context) }

    // Server base URL（只读，不允许在 App 内修改）。
    val currentBaseUrl = remember { ServerConfigStore.readBaseUrl(context) }
    var connectionTesting by remember { mutableStateOf(false) }
    var connectionOk by remember { mutableStateOf<Boolean?>(null) }

    // Auth form.
    var loginUsername by rememberSaveable { mutableStateOf("") }
    var loginPassword by rememberSaveable { mutableStateOf("") }
    var authBusy by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<UiError?>(null) }

    // Register dialog.
    var showRegisterDialog by rememberSaveable { mutableStateOf(false) }
    var registerUsername by rememberSaveable { mutableStateOf("") }
    var registerEmail by rememberSaveable { mutableStateOf("") }
    var registerPassword by rememberSaveable { mutableStateOf("") }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    if (showRegisterDialog) {
        AlertDialog(
            onDismissRequest = { showRegisterDialog = false },
            title = { Text(stringResource(R.string.settings_register)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = registerUsername,
                        onValueChange = { registerUsername = it },
                        label = { Text(stringResource(R.string.settings_username)) },
                        singleLine = true,
                        enabled = !authBusy,
                    )
                    OutlinedTextField(
                        value = registerEmail,
                        onValueChange = { registerEmail = it },
                        label = { Text(stringResource(R.string.settings_email_optional)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        enabled = !authBusy,
                    )
                    OutlinedTextField(
                        value = registerPassword,
                        onValueChange = { registerPassword = it },
                        label = { Text(stringResource(R.string.settings_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !authBusy,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !authBusy,
                    onClick = {
                        authBusy = true
                        authError = null
                        syncManager.registerAndLoginAsync(
                            username = registerUsername.trim(),
                            email = registerEmail.trim().ifBlank { null },
                            password = registerPassword,
                            onResult = {
                                authBusy = false
                                showRegisterDialog = false
                                AuthGateStore.dismiss(context)
                                WayfarerSyncScheduler.enqueueOneTimeSync(context)
                                onLoginSuccess()
                            },
                            onError = { err ->
                                authBusy = false
                                authError = UiErrorFormatter.format(err)
                            },
                        )
                    },
                ) {
                    Text(stringResource(R.string.settings_register_and_login))
                }
            },
            dismissButton = {
                TextButton(enabled = !authBusy, onClick = { showRegisterDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.auth_gate_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.auth_gate_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.auth_gate_section_server),
                    style = MaterialTheme.typography.titleMedium,
                )

                Text(
                    text = stringResource(R.string.settings_server_url_current, currentBaseUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )

                Row {
                    Button(
                        enabled = !connectionTesting && !authBusy,
                        onClick = {
                            connectionTesting = true
                            connectionOk = null
                            syncManager.testConnectionAsync(
                                onResult = {
                                    connectionTesting = false
                                    connectionOk = it
                                },
                                onError = {
                                    connectionTesting = false
                                    connectionOk = false
                                },
                            )
                        },
                    ) {
                        Text(
                            text = if (connectionTesting) {
                                stringResource(R.string.settings_sync_action_running)
                            } else {
                                stringResource(R.string.settings_test_connection)
                            },
                        )
                    }
                }

                if (connectionOk != null) {
                    Text(
                        text = if (connectionOk == true) {
                            stringResource(R.string.settings_connection_ok)
                        } else {
                            stringResource(R.string.settings_connection_failed)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (connectionOk == true) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }

        OutlinedCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.auth_gate_section_account),
                    style = MaterialTheme.typography.titleMedium,
                )

                if (authError != null) {
                    Text(
                        text = authError?.message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    val detail = authError?.detail
                    if (!detail.isNullOrBlank()) {
                        TextButton(onClick = { copyToClipboard("Wayfarer error", detail) }) {
                            Text(stringResource(R.string.auth_gate_copy_error))
                        }
                    }
                }

                OutlinedTextField(
                    value = loginUsername,
                    onValueChange = { loginUsername = it },
                    label = { Text(stringResource(R.string.settings_username)) },
                    singleLine = true,
                    enabled = !authBusy,
                )
                OutlinedTextField(
                    value = loginPassword,
                    onValueChange = { loginPassword = it },
                    label = { Text(stringResource(R.string.settings_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !authBusy,
                )

                Row {
                    Button(
                        enabled = !authBusy,
                        onClick = {
                            authBusy = true
                            authError = null
                            syncManager.loginAsync(
                                username = loginUsername.trim(),
                                password = loginPassword,
                                onResult = {
                                    authBusy = false
                                    loginPassword = ""
                                    AuthGateStore.dismiss(context)
                                    WayfarerSyncScheduler.enqueueOneTimeSync(context)
                                    onLoginSuccess()
                                },
                                onError = { err ->
                                    authBusy = false
                                    authError = UiErrorFormatter.format(err)
                                },
                            )
                        },
                    ) {
                        Text(
                            text = if (authBusy) {
                                stringResource(R.string.settings_sync_action_running)
                            } else {
                                stringResource(R.string.settings_login)
                            },
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    FilledTonalButton(
                        enabled = !authBusy,
                        onClick = {
                            authError = null
                            showRegisterDialog = true
                        },
                    ) {
                        Text(stringResource(R.string.settings_register))
                    }
                }
            }
        }

        OutlinedCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.auth_gate_section_offline),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.auth_gate_offline_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        AuthGateStore.dismiss(context)
                        onContinueOffline()
                    },
                ) {
                    Text(stringResource(R.string.auth_gate_continue_offline))
                }
            }
        }
    }
}
