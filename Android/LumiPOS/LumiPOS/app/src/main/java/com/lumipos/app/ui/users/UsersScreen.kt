package com.lumipos.app.ui.users

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.lumipos.data.schema.UserEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen(
    onBack: (() -> Unit)? = null,
    viewModel: UsersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<UserEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Users") },
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
                Icon(Icons.Filled.Add, contentDescription = "Add User")
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingState(Modifier.padding(padding))
        } else {
            if (uiState.users.isEmpty()) {
                EmptyState("No users yet.")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.padding(padding).fillMaxSize()
                ) {
                    items(uiState.users, key = { it.id }) { user ->
                        UserCard(
                            user = user,
                            roleName = uiState.roleById[user.roleId]?.removePrefix("ROLE_") ?: "Unknown",
                            isAdmin = uiState.isAdmin,
                            currentUserId = uiState.currentUserId,
                            onEdit = { viewModel.openEdit(user) },
                            onToggleActive = { viewModel.toggleActive(user) },
                            onChangePassword = { viewModel.openChangePassword(user.id) },
                            onResetLockout = { viewModel.resetLockout(user) },
                            onDelete = { deleteTarget = user }
                        )
                    }
                }
            }
        }
    }

    // Add/Edit Form
    if (uiState.showForm) {
        UserFormSheet(
            uiState = uiState,
            viewModel = viewModel
        )
    }

    // Change Password
    if (uiState.showChangePasswordDialog) {
        AlertDialog(
            onDismissRequest = { if (!uiState.isSubmitting) viewModel.dismissChangePassword() },
            title = { Text("Change Password") },
            text = {
                OutlinedTextField(
                    value = uiState.newPassword,
                    onValueChange = viewModel::onNewPasswordChange,
                    label = { Text("New password (min 6 chars)") },
                    singleLine = true,
                    enabled = !uiState.isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::submitChangePassword, enabled = !uiState.isSubmitting && uiState.newPassword.length >= 6) {
                    Text(if (uiState.isSubmitting) "Saving..." else "Update")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissChangePassword, enabled = !uiState.isSubmitting) { Text("Cancel") }
            }
        )
    }

    // Delete Confirm
    deleteTarget?.let { user ->
        ConfirmDialog(
            title = "Delete User",
            message = "Delete user '${user.username}'? This cannot be undone.",
            confirmText = "Delete",
            onConfirm = {
                viewModel.deleteUser(user)
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

@Composable
private fun UserCard(
    user: UserEntity,
    roleName: String,
    isAdmin: Boolean,
    currentUserId: Long,
    onEdit: () -> Unit,
    onToggleActive: () -> Unit,
    onChangePassword: () -> Unit,
    onResetLockout: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${user.firstName} ${user.lastName}".trim().ifBlank { user.username },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("@${user.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    AssistChip(
                        onClick = {},
                        label = { Text(roleName) }
                    )
                    if (!user.isActive) {
                        Text("Disabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if ((user.failedLoginAttempts > 0 || user.lockedUntil != null) && user.id != currentUserId) {
                        Text("Locked (${user.failedLoginAttempts})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (isAdmin && user.id != currentUserId) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onChangePassword) { Icon(Icons.Filled.Password, "Password") }
                    if (user.lockedUntil != null || user.failedLoginAttempts > 0) {
                        IconButton(onClick = onResetLockout) { Icon(Icons.Filled.LockOpen, "Reset lockout") }
                    }
                    IconButton(onClick = onToggleActive) {
                        Icon(
                            if (user.isActive) Icons.Filled.LockOpen else Icons.Filled.Lock,
                            contentDescription = if (user.isActive) "Disable" else "Enable"
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserFormSheet(
    uiState: UsersUiState,
    viewModel: UsersViewModel
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isEditing = uiState.editingUserId != 0L

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = viewModel::dismissForm
    ) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (isEditing) "Edit User" else "New User",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = uiState.firstName,
                onValueChange = viewModel::onFirstNameChange,
                label = { Text("First name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.lastName,
                onValueChange = viewModel::onLastNameChange,
                label = { Text("Last name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text("Username *") },
                singleLine = true,
                enabled = !isEditing,
                modifier = Modifier.fillMaxWidth()
            )
            if (!isEditing) {
                OutlinedTextField(
                    value = uiState.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = { Text("Password * (min 6 chars)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = uiState.pin,
                onValueChange = viewModel::onPinChange,
                label = { Text("PIN (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            // Role dropdown
            Text("Role", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var showRoleDialog by remember { mutableStateOf(false) }
            Text(
                uiState.roles.find { it.id == uiState.roleId }?.name?.removePrefix("ROLE_")?.lowercase()?.replaceFirstChar { c -> c.uppercaseChar() } ?: "Select role",
                modifier = Modifier.fillMaxWidth().clickable { showRoleDialog = true }.padding(12.dp)
            )
            if (showRoleDialog) {
                AlertDialog(
                    onDismissRequest = { showRoleDialog = false },
                    confirmButton = {},
                    text = {
                        Column {
                            uiState.roles.forEach { role ->
                                ListItem(
                                    headlineContent = { Text(role.name.removePrefix("ROLE_")) },
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        viewModel.onRoleIdChange(role.id)
                                        showRoleDialog = false
                                    }
                                )
                            }
                        }
                    }
                )
            }
            Button(
                onClick = viewModel::submitForm,
                enabled = !uiState.isSubmitting && uiState.username.isNotBlank() && (isEditing || uiState.password.length >= 6),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (uiState.isSubmitting) "Saving..." else if (isEditing) "Update" else "Create")
            }
        }
    }
}
