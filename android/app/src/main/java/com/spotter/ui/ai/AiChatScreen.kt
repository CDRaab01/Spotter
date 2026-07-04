package com.spotter.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.data.model.SuggestedRoutine
import com.spotter.ui.components.EmptyState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import com.spotter.ui.theme.SpotterTheme
import com.spotter.util.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    navController: NavController,
    viewModel: AiChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsState()
    val sendState by viewModel.sendState.collectAsState()
    val pendingRoutine by viewModel.pendingRoutine.collectAsState()
    val pendingProgram by viewModel.pendingProgram.collectAsState()
    val pendingAdjustment by viewModel.pendingAdjustment.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var overflowExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val isLoading = sendState is UiState.Loading

    val itemCount = messages.size + if (isLoading) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    LaunchedEffect(sendState) {
        if (sendState is UiState.Error) {
            snackbarHostState.showSnackbar(
                message = (sendState as UiState.Error).message,
                duration = SnackbarDuration.Long,
            )
            viewModel.clearError()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.routineSaved.collect { routineName ->
            snackbarHostState.showSnackbar(
                message = "Routine \"$routineName\" saved!",
                duration = SnackbarDuration.Short,
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.programSaved.collect { programName ->
            snackbarHostState.showSnackbar(
                message = "Program \"$programName\" saved & activated!",
                duration = SnackbarDuration.Short,
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.adjustmentApplied.collect { count ->
            snackbarHostState.showSnackbar(
                message = "Workout updated — $count change${if (count != 1) "s" else ""} applied.",
                duration = SnackbarDuration.Short,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Coach") },
                navigationIcon = {
                    // Only chats opened from an active workout get a back affordance;
                    // as a bottom-nav tab the screen needs none.
                    if (viewModel.sessionAware) {
                        IconButton(onClick = {
                            if (!navController.popBackStack()) {
                                navController.navigate(com.spotter.ui.navigation.Screen.Home.route) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Clear history") },
                                onClick = {
                                    overflowExpanded = false
                                    viewModel.clearHistory()
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (messages.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Default.AutoAwesome,
                        title = "Meet your AI Coach",
                        subtitle = "Ask anything about training, form, or your plan — " +
                            "or have it build a workout for you.",
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(messages) { msg -> ChatBubble(msg) }
                    if (isLoading) {
                        item { TypingIndicator() }
                    }
                }
            }

            AnimatedVisibility(visible = pendingRoutine != null) {
                pendingRoutine?.let { routine ->
                    SuggestedRoutineCard(
                        routine = routine,
                        onSave = { viewModel.saveRoutine() },
                        onDismiss = { viewModel.dismissRoutine() },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            AnimatedVisibility(visible = pendingProgram != null) {
                pendingProgram?.let { program ->
                    SuggestedProgramCard(
                        program = program,
                        onSave = { viewModel.saveProgram() },
                        onDismiss = { viewModel.dismissProgram() },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            AnimatedVisibility(visible = pendingAdjustment != null) {
                pendingAdjustment?.let { adjustment ->
                    SuggestedAdjustmentCard(
                        adjustment = adjustment,
                        onApply = { applyToRoutine -> viewModel.applyAdjustment(applyToRoutine) },
                        onDismiss = { viewModel.dismissAdjustment() },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Ask your coach…") },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        viewModel.send(inputText)
                        inputText = ""
                    },
                    enabled = inputText.isNotBlank() && !isLoading,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (inputText.isNotBlank() && !isLoading) SpotterTheme.pulse.effort
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestedRoutineCard(
    routine: SuggestedRoutine,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(
        modifier = modifier.fillMaxWidth(),
        channel = SpotterTheme.pulse.effort,
        contentPadding = 12.dp,
    ) {
        Text(
            text = routine.name,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "${routine.exercises.size} exercise${if (routine.exercises.size != 1) "s" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulseButton(
                text = "Save Routine",
                onClick = onSave,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun SuggestedProgramCard(
    program: com.spotter.data.model.SuggestedProgram,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(
        modifier = modifier.fillMaxWidth(),
        channel = SpotterTheme.pulse.effort,
        contentPadding = 12.dp,
    ) {
        Text(
            text = program.name,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "${program.days.size}-day program",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        program.days.forEach { day ->
            val detail = if (day.exercises.isEmpty()) "Rest"
                else "${day.exercises.size} lift${if (day.exercises.size != 1) "s" else ""}"
            Text(
                text = "• ${day.label} · $detail",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = "Saving makes this your active program.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulseButton(
                text = "Save Program",
                onClick = onSave,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun SuggestedAdjustmentCard(
    adjustment: com.spotter.data.model.SuggestedAdjustment,
    onApply: (applyToRoutine: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var applyToRoutine by remember { mutableStateOf(true) }
    PanelCard(
        modifier = modifier.fillMaxWidth(),
        channel = SpotterTheme.pulse.effort,
        contentPadding = 12.dp,
    ) {
        Text(
            text = "Workout adjustment",
            style = MaterialTheme.typography.titleSmall,
        )
        adjustment.actions.forEach { action ->
            Text(
                text = "• ${action.summary}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Also update future workouts",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (applyToRoutine) "Changes your program too" else "This session only",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = applyToRoutine,
                onCheckedChange = { applyToRoutine = it },
            )
        }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulseButton(
                text = "Apply",
                onClick = { onApply(applyToRoutine) },
                compact = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    val alpha0 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(0),
        ), label = "dot0",
    )
    val alpha1 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(150),
        ), label = "dot1",
    )
    val alpha2 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(300),
        ), label = "dot2",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = SpotterTheme.pulse.panel,
                    shape = RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomEnd = 16.dp, bottomStart = 4.dp,
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(alpha0, alpha1, alpha2).forEach { alpha ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = SpotterTheme.pulse.effort.copy(alpha = alpha),
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }
    }
}
