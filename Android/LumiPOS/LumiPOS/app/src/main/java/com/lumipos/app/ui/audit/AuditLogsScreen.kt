package com.lumipos.app.ui.audit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.lumipos.app.ui.util.asDateTime
import com.lumipos.data.schema.AuditLogEntity

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AuditLogsScreen(
    onBack: () -> Unit,
    viewModel: AuditLogsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audit Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingState(Modifier.padding(padding))
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AuditRange.entries.forEach { range ->
                        FilterChip(
                            selected = uiState.range == range,
                            onClick = { viewModel.setRange(range) },
                            label = { Text(range.label) }
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.actionFilter == null,
                        onClick = { viewModel.setActionFilter(null) },
                        label = { Text("All Actions") }
                    )
                    uiState.actions.forEach { action ->
                        FilterChip(
                            selected = uiState.actionFilter == action,
                            onClick = { viewModel.setActionFilter(action) },
                            label = { Text(action) }
                        )
                    }
                }
                val filtered = uiState.logs.filter {
                    uiState.actionFilter == null || it.action == uiState.actionFilter
                }
                if (filtered.isEmpty()) {
                    EmptyState("No logs in this range.")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(filtered, key = { it.id }) { log ->
                            AuditLogCard(log = log, userName = userName(log, uiState))
                        }
                    }
                }
            }
        }
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

private fun userName(log: AuditLogEntity, uiState: AuditLogsUiState): String {
    val userId = log.userId ?: return "System"
    val user = uiState.usersById[userId] ?: return "User #$userId"
    val first = user.firstName.takeIf { it.isNotBlank() } ?: ""
    val last = user.lastName.takeIf { it.isNotBlank() } ?: ""
    return "$first $last".trim().ifBlank { user.username }
}

@Composable
private fun AuditLogCard(log: AuditLogEntity, userName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    log.action,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.padding(top = 2.dp))
            Text(log.createdAt.asDateTime(), style = MaterialTheme.typography.bodySmall)
            Text("By: $userName", style = MaterialTheme.typography.bodySmall)
            if (log.entityType.isNotBlank() || log.detail.isNotBlank()) {
                Text(
                    "${log.entityType}" + if (log.entityId != null) " #${log.entityId}" else "" + if (log.detail.isNotBlank()) " - ${log.detail}" else "",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}