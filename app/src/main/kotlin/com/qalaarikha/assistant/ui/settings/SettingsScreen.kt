package com.qalaarikha.assistant.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qalaarikha.assistant.App
import com.qalaarikha.assistant.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            LocalContext.current.applicationContext as App
        )
    )
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    // Show reload confirmation snackbar
    LaunchedEffect(state.reloadMessage) {
        val msg = state.reloadMessage
        if (msg != null) {
            snackbarHost.showSnackbar(msg)
            vm.clearReloadMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "חזור")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {

            // ── Section: Speech ───────────────────────────────────────────────
            SectionHeader(text = "זיהוי דיבור")

            // Fuzzy threshold slider
            SettingsCard {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_fuzzy_threshold),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "דיוק התאמת שמות אנשי קשר",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text  = stringResource(R.string.settings_fuzzy_threshold_desc, state.fuzzyThreshold),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value         = state.fuzzyThreshold.toFloat(),
                        onValueChange = { vm.setFuzzyThreshold(it.toInt()) },
                        valueRange    = 50f..100f,
                        steps         = 9,           // 50,55,60,...,100
                        modifier      = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("50%", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("100%", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Silence length slider
            SettingsCard {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_silence_length),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "זמן שתיקה לסיום זיהוי דיבור",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text  = "${state.silenceLengthMs} ms",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value         = state.silenceLengthMs.toFloat(),
                        onValueChange = { vm.setSilenceLengthMs(it.toLong()) },
                        valueRange    = 500f..4000f,
                        steps         = 6,
                        modifier      = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("500ms", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("4000ms", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── Section: Dialing ──────────────────────────────────────────────
            SectionHeader(text = "חיוג")

            // Dial immediately toggle
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_dial_immediately),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.settings_dial_immediately_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked         = state.dialImmediately,
                        onCheckedChange = vm::setDialImmediately
                    )
                }
            }

            // ── Section: Contacts ─────────────────────────────────────────────
            SectionHeader(text = "אנשי קשר")

            // Contacts file path
            var filePathDraft by remember { mutableStateOf(state.contactsFilePath) }
            LaunchedEffect(state.contactsFilePath) { filePathDraft = state.contactsFilePath }

            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.settings_contacts_file),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "ברירת מחדל: Android/data/com.qalaarikha.assistant/files/contacts.txt",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value         = filePathDraft,
                        onValueChange = { filePathDraft = it },
                        label         = { Text(stringResource(R.string.settings_contacts_file_hint)) },
                        singleLine    = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { vm.setContactsFilePath(filePathDraft) }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (filePathDraft != state.contactsFilePath) {
                                IconButton(onClick = { vm.setContactsFilePath(filePathDraft) }) {
                                    Icon(Icons.Default.Check, contentDescription = "שמור")
                                }
                            }
                        }
                    )
                }
            }

            // Reload contacts button
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_reload_contacts),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        if (state.contactCount > 0) {
                            Text(
                                "כרגע: ${state.contactCount} אנשי קשר",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    FilledTonalButton(onClick = vm::reloadContacts) {
                        Icon(Icons.Default.Refresh, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("טען")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Box(modifier = Modifier.padding(16.dp)) { content() }
    }
}
