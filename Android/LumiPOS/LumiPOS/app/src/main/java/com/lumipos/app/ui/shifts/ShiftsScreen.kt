package com.lumipos.app.ui.shifts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumipos.app.ui.components.EmptyState
import com.lumipos.app.ui.components.LoadingState
import com.lumipos.app.ui.util.asCurrency
import com.lumipos.app.ui.util.asDateTime
import com.lumipos.data.schema.CashShiftEntity

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ShiftsScreen(
    onBack: (() -> Unit)? = null,
    viewModel: ShiftsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cash Shifts") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingState(Modifier.padding(padding))
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                CurrentShiftCard(
                    shift = uiState.currentShift,
                    onOpen = viewModel::openOpenDialog,
                    onClose = viewModel::openCloseDialog
                )
                Spacer(Modifier.height(12.dp))
                Text("Shift History", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))
                if (uiState.shifts.isEmpty()) {
                    EmptyState("No shifts recorded yet.")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(uiState.shifts, key = { it.id }) { shift ->
                            ShiftHistoryCard(shift)
                        }
                    }
                }
            }
        }
    }

    if (uiState.showOpenDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissOpenDialog,
            title = { Text("Open Cash Shift") },
            text = {
                Column {
                    Text("Count the starting cash and enter the amount.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = uiState.openingAmount,
                        onValueChange = viewModel::setOpeningAmount,
                        label = { Text("Opening Amount") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.openingNotes,
                        onValueChange = viewModel::setOpeningNotes,
                        label = { Text("Notes (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = viewModel::openShift, enabled = !uiState.isSaving) {
                    Text(if (uiState.isSaving) "Opening..." else "Open Shift")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissOpenDialog) { Text("Cancel") }
            }
        )
    }

    if (uiState.showCloseDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCloseDialog,
            title = { Text("Close Cash Shift") },
            text = {
                Column {
                    Text("Count the drawer and enter the counted amount.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = uiState.closingAmount,
                        onValueChange = viewModel::setClosingAmount,
                        label = { Text("Counted Closing Amount") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.closeNotes,
                        onValueChange = viewModel::setCloseNotes,
                        label = { Text("Notes (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::closeShift,
                    enabled = !uiState.isSaving && uiState.closingAmount.toDoubleOrNull() != null
                ) {
                    Text(if (uiState.isSaving) "Closing..." else "Close Shift")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCloseDialog) { Text("Cancel") }
            }
        )
    }

    uiState.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } },
            title = { Text("Error") },
            text = { Text(error) }
        )
    }
}

@Composable
private fun CurrentShiftCard(
    shift: CashShiftEntity?,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (shift != null) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                if (shift != null) "Shift OPEN" else "No open shift",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            if (shift != null) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Opening amount", modifier = Modifier.weight(1f))
                    Text(shift.openingAmount.asCurrency(), fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.fillMaxWidth()) {
                    Text("Opened at", modifier = Modifier.weight(1f))
                    Text(shift.openingTime.asDateTime())
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Close Shift")
                }
            } else {
                Text("Open a shift before taking payments to track cash per shift.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Shift")
                }
            }
        }
    }
}

@Composable
private fun ShiftHistoryCard(shift: CashShiftEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Shift #${shift.id}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    shift.status,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (shift.status == "OPEN") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.height(4.dp))
            Text("Opened: ${shift.openingTime.asDateTime()}", style = MaterialTheme.typography.bodySmall)
            if (shift.status == "CLOSED") {
                Spacer(Modifier.height(4.dp))
                Text("Closed: ${shift.closingTime?.asDateTime() ?: "-"}", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("Opening", modifier = Modifier.weight(1f))
                    Text(shift.openingAmount.asCurrency())
                }
                Row(Modifier.fillMaxWidth()) {
                    Text("Expected", modifier = Modifier.weight(1f))
                    Text(shift.expectedAmount?.asCurrency() ?: "-")
                }
                Row(Modifier.fillMaxWidth()) {
                    Text("Counted", modifier = Modifier.weight(1f))
                    Text(shift.closingAmount?.asCurrency() ?: "-")
                }
                val variance = shift.variance
                Row(Modifier.fillMaxWidth()) {
                    Text("Variance", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(
                        variance?.asCurrency() ?: "-",
                        fontWeight = FontWeight.Bold,
                        color = when {
                            variance == null -> MaterialTheme.colorScheme.onSurface
                            variance < 0 -> MaterialTheme.colorScheme.error
                            variance > 0 -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
            if (shift.notes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Note: ${shift.notes}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}