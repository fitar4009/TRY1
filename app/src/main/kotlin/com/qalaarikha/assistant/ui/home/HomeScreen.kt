package com.qalaarikha.assistant.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qalaarikha.assistant.App
import com.qalaarikha.assistant.R
import com.qalaarikha.assistant.data.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    vm: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory(
            LocalContext.current.applicationContext as App
        )
    )
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    // Handle one-shot effects
    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                is UiEffect.LaunchCall -> {
                    val phone = effect.phoneNumber
                    val intent = try {
                        Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    } catch (e: Exception) {
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                is UiEffect.ShowSnackbar -> {
                    snackbarHost.showSnackbar(effect.message)
                }
                is UiEffect.CopyToClipboard -> {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("dictation", effect.text))
                    snackbarHost.showSnackbar("הועתק ללוח")
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    // Contact count badge
                    if (uiState.contactCount > 0) {
                        Text(
                            text = stringResource(R.string.label_contacts_loaded, uiState.contactCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Offline warning ───────────────────────────────────────────────
            if (uiState.showOfflineWarning) {
                OfflineWarningBanner()
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(24.dp))

            // ── Listening orb ─────────────────────────────────────────────────
            ListeningOrb(
                assistantState = uiState.assistantState,
                rmsLevel       = uiState.rmsLevel
            )

            Spacer(Modifier.height(20.dp))

            // ── State label ───────────────────────────────────────────────────
            StateLabel(uiState.assistantState)

            // ── Partial transcript ────────────────────────────────────────────
            if (uiState.partialText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.label_partial, uiState.partialText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── Content area (state-dependent) ───────────────────────────────
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val state = uiState.assistantState) {

                    is AssistantState.WaitingForSelection ->
                        CandidateList(state.candidates)

                    is AssistantState.WaitingForConfirmation ->
                        ConfirmationCard(state)

                    is AssistantState.Dictating ->
                        DictationArea(
                            text      = uiState.dictationText,
                            onCopy    = vm::copyDictationText,
                            onStop    = vm::exitDictation
                        )

                    is AssistantState.Error ->
                        ErrorCard(state.message)

                    else -> { /* nothing extra */ }
                }
            }

            // ── Mic toggle button ─────────────────────────────────────────────
            val isListening = uiState.assistantState is AssistantState.Listening
            val isDictating = uiState.assistantState is AssistantState.Dictating

            if (!isDictating) {
                FloatingActionButton(
                    onClick = vm::toggleListening,
                    containerColor = if (isListening)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isListening)
                            stringResource(R.string.btn_stop_listening)
                        else
                            stringResource(R.string.btn_start_listening)
                    )
                }
            }
        }
    }
}

// ── Animated orb ─────────────────────────────────────────────────────────────

@Composable
private fun ListeningOrb(assistantState: AssistantState, rmsLevel: Float) {
    val isListening = assistantState is AssistantState.Listening
    val isSpeaking  = assistantState is AssistantState.Speaking

    // Pulsing animation when listening
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseAnim.animateFloat(
        initialValue = 1f,
        targetValue  = if (isListening) 1f + (rmsLevel * 0.25f) else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Gentle throb when speaking
    val speakScale by pulseAnim.animateFloat(
        initialValue = 1f,
        targetValue  = if (isSpeaking) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speakScale"
    )

    val orbColor = when (assistantState) {
        is AssistantState.Listening   -> MaterialTheme.colorScheme.primary
        is AssistantState.Speaking    -> MaterialTheme.colorScheme.tertiary
        is AssistantState.Processing  -> MaterialTheme.colorScheme.secondary
        is AssistantState.Error       -> MaterialTheme.colorScheme.error
        is AssistantState.Dictating   -> MaterialTheme.colorScheme.secondary
        else                          -> MaterialTheme.colorScheme.surfaceVariant
    }

    val finalScale = if (isListening) pulseScale else if (isSpeaking) speakScale else 1f

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(140.dp)
            .scale(finalScale)
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(orbColor, orbColor.copy(alpha = 0.6f))
                )
            )
    ) {
        val icon = when (assistantState) {
            is AssistantState.Listening  -> Icons.Default.Mic
            is AssistantState.Speaking   -> Icons.Default.VolumeUp
            is AssistantState.Processing -> Icons.Default.Psychology
            is AssistantState.Error      -> Icons.Default.ErrorOutline
            is AssistantState.Dictating  -> Icons.Default.Edit
            else                         -> Icons.Default.MicNone
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(56.dp)
        )
    }
}

// ── State label ───────────────────────────────────────────────────────────────

@Composable
private fun StateLabel(state: AssistantState) {
    val (labelRes, color) = when (state) {
        is AssistantState.Listening            ->
            R.string.state_listening to MaterialTheme.colorScheme.primary
        is AssistantState.Processing           ->
            R.string.state_processing to MaterialTheme.colorScheme.secondary
        is AssistantState.Speaking             ->
            R.string.state_speaking to MaterialTheme.colorScheme.tertiary
        is AssistantState.Error                ->
            R.string.state_error to MaterialTheme.colorScheme.error
        is AssistantState.Dictating            ->
            R.string.state_dictating to MaterialTheme.colorScheme.secondary
        is AssistantState.WaitingForSelection,
        is AssistantState.WaitingForConfirmation ->
            R.string.state_listening to MaterialTheme.colorScheme.primary
        else                                   ->
            R.string.state_idle to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text      = stringResource(labelRes),
        style     = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color     = color
    )
}

// ── Candidate list ────────────────────────────────────────────────────────────

@Composable
private fun CandidateList(candidates: List<ContactCandidate>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text  = stringResource(R.string.label_candidates),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        candidates.forEachIndexed { index, cc ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Number badge
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            text  = "${index + 1}",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text  = cc.contact.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text  = cc.contact.phoneNumber,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Confidence score badge
                    Text(
                        text  = "${(cc.score * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Confirmation card ─────────────────────────────────────────────────────────

@Composable
private fun ConfirmationCard(state: AssistantState.WaitingForConfirmation) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Phone,
                contentDescription = null,
                tint   = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text  = state.displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center
            )
            Text(
                text  = state.phoneNumber,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text  = "אמור \"כן\" לחיוג או \"לא\" לביטול",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

// ── Dictation area ────────────────────────────────────────────────────────────

@Composable
private fun DictationArea(text: String, onCopy: () -> Unit, onStop: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text  = stringResource(R.string.label_dictation_text),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row {
                IconButton(onClick = onCopy) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.btn_copy_text))
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.StopCircle, contentDescription = stringResource(R.string.btn_exit_dictation))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            shape  = RoundedCornerShape(12.dp),
            color  = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp
        ) {
            Text(
                text     = text.ifBlank { "..." },
                modifier = Modifier.padding(16.dp),
                style    = MaterialTheme.typography.bodyLarge,
                color    = if (text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                           else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ── Error card ────────────────────────────────────────────────────────────────

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null,
                tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Text(
                text  = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

// ── Offline warning banner ────────────────────────────────────────────────────

@Composable
private fun OfflineWarningBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Default.Warning, contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text  = stringResource(R.string.offline_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}
