package com.lumipos.app.ui.customers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumipos.app.ui.components.ConfirmDialog
import com.lumipos.app.ui.components.EmptyState
import com.lumipos.app.ui.components.LoadingState
import com.lumipos.data.schema.CustomerEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomersScreen(
    onBack: (() -> Unit)? = null,
    viewModel: CustomersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<CustomerEntity?>(null) }

    BoxWithConstraints {
        val isExpanded = maxWidth >= 600.dp
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Customers") },
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
                    Icon(Icons.Filled.Add, contentDescription = "Add Customer")
                }
            }
        ) { padding ->
            if (uiState.isLoading) {
                LoadingState(Modifier.padding(padding))
            } else if (isExpanded) {
                Row(Modifier.fillMaxSize().padding(padding)) {
                    CustomerListPane(
                        customers = uiState.customers.filter {
                            uiState.searchQuery.isBlank() || it.name.contains(uiState.searchQuery, ignoreCase = true)
                        },
                        onSelect = viewModel::openEdit,
                        onDelete = { deleteTarget = it },
                        searchQuery = uiState.searchQuery,
                        onSearchChange = viewModel::onSearchChange,
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.showForm) {
                        CustomerFormPane(
                            form = uiState.form,
                            isEditing = uiState.editingCustomer != null,
                            onNameChange = viewModel::onNameChange,
                            onPhoneChange = viewModel::onPhoneChange,
                            onEmailChange = viewModel::onEmailChange,
                            onAddressChange = viewModel::onAddressChange,
                            onSave = viewModel::saveCustomer,
                            onClose = viewModel::closeForm,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text("Select a customer to edit", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                CustomerListPane(
                    customers = uiState.customers.filter {
                        uiState.searchQuery.isBlank() || it.name.contains(uiState.searchQuery, ignoreCase = true)
                    },
                    onSelect = viewModel::openEdit,
                    onDelete = { deleteTarget = it },
                    searchQuery = uiState.searchQuery,
                    onSearchChange = viewModel::onSearchChange,
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            }
            if (!isExpanded && uiState.showForm) {
                ModalBottomSheet(onDismissRequest = viewModel::closeForm) {
                    CustomerFormPane(
                        form = uiState.form,
                        isEditing = uiState.editingCustomer != null,
                        onNameChange = viewModel::onNameChange,
                        onPhoneChange = viewModel::onPhoneChange,
                        onEmailChange = viewModel::onEmailChange,
                        onAddressChange = viewModel::onAddressChange,
                        onSave = viewModel::saveCustomer,
                        onClose = viewModel::closeForm
                    )
                }
            }
            uiState.error?.let { error ->
                AlertDialog(
                    onDismissRequest = { viewModel.closeForm() },
                    confirmButton = { TextButton(onClick = { viewModel.closeForm() }) { Text("OK") } },
                    title = { Text("Error") },
                    text = { Text(error) }
                )
            }
            deleteTarget?.let { c ->
                ConfirmDialog(
                    title = "Delete Customer",
                    message = "Delete customer \"${c.name}\"?",
                    confirmText = "Delete",
                    onConfirm = { viewModel.deleteCustomer(c); deleteTarget = null },
                    onDismiss = { deleteTarget = null }
                )
            }
        }
    }
}

@Composable
private fun CustomerListPane(
    customers: List<CustomerEntity>,
    onSelect: (CustomerEntity) -> Unit,
    onDelete: (CustomerEntity) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            label = { Text("Search customers") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true
        )
        if (customers.isEmpty()) {
            EmptyState("No customers yet.\nTap + to add one.")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(customers, key = { it.id }) { c ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(c) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium)
                                if (c.phone.isNotBlank()) Text(c.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onDelete(c) }) {
                                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomerFormPane(
    form: CustomerFormState,
    isEditing: Boolean,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
        Text(
            text = if (isEditing) "Edit Customer" else "Add Customer",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = form.name,
            onValueChange = onNameChange,
            label = { Text("Name *") },
            singleLine = true,
            isError = form.formError != null,
            supportingText = form.formError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.phone,
            onValueChange = onPhoneChange,
            label = { Text("Phone") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.email,
            onValueChange = onEmailChange,
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.address,
            onValueChange = onAddressChange,
            label = { Text("Address") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(if (isEditing) "Save Changes" else "Add Customer")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}