package com.lumipos.app.ui.suppliers

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumipos.app.ui.components.ConfirmDialog
import com.lumipos.app.ui.components.EmptyState
import com.lumipos.app.ui.components.LoadingState
import com.lumipos.data.schema.SupplierEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuppliersScreen(
    onBack: (() -> Unit)? = null,
    viewModel: SuppliersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<SupplierEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Suppliers") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::openAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Add Supplier")
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingState(Modifier.padding(padding))
        } else if (uiState.suppliers.isEmpty()) {
            EmptyState("No suppliers yet.\nTap + to add one.", Modifier.padding(padding))
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.padding(padding).fillMaxSize()
            ) {
                items(uiState.suppliers, key = { it.id }) { supplier ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.openEdit(supplier) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(supplier.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                if (supplier.phone.isNotBlank()) {
                                    Text(supplier.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (supplier.address.isNotBlank()) {
                                    Text(supplier.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { deleteTarget = supplier }) {
                                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (uiState.showForm) {
        SupplierFormSheet(uiState = uiState, viewModel = viewModel)
    }

    deleteTarget?.let { supplier ->
        ConfirmDialog(
            title = "Delete Supplier",
            message = "Delete supplier '${supplier.name}'?",
            confirmText = "Delete",
            onConfirm = {
                viewModel.deleteSupplier(supplier)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
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

    uiState.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
            title = { Text("Done") },
            text = { Text(message) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupplierFormSheet(
    uiState: SuppliersUiState,
    viewModel: SuppliersViewModel
) {
    val isEditing = uiState.editingSupplier != null
    ModalBottomSheet(onDismissRequest = viewModel::closeForm) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 24.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (isEditing) "Edit Supplier" else "Add Supplier",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = uiState.form.name,
                onValueChange = { viewModel.onFieldChange("name", it) },
                label = { Text("Name *") },
                isError = uiState.form.error != null,
                supportingText = uiState.form.error?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.form.contactPerson,
                onValueChange = { viewModel.onFieldChange("contactPerson", it) },
                label = { Text("Contact person") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.form.phone,
                onValueChange = { viewModel.onFieldChange("phone", it) },
                label = { Text("Phone") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.form.email,
                onValueChange = { viewModel.onFieldChange("email", it) },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.form.address,
                onValueChange = { viewModel.onFieldChange("address", it) },
                label = { Text("Address") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.form.taxId,
                onValueChange = { viewModel.onFieldChange("taxId", it) },
                label = { Text("Tax ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.form.paymentTerms,
                onValueChange = { viewModel.onFieldChange("paymentTerms", it) },
                label = { Text("Payment terms") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.form.notes,
                onValueChange = { viewModel.onFieldChange("notes", it) },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = viewModel::save, enabled = uiState.form.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (isEditing) "Save Changes" else "Add Supplier")
            }
        }
    }
}