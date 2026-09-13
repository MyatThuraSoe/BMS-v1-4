package com.lumipos.app.ui.categories

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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.BoxWithConstraints
import com.lumipos.app.ui.components.ConfirmDialog
import com.lumipos.app.ui.components.EmptyState
import com.lumipos.app.ui.components.LoadingState
import com.lumipos.data.schema.CategoryEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: (() -> Unit)? = null,
    viewModel: CategoriesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<CategoryEntity?>(null) }

    BoxWithConstraints {
        val isExpanded = maxWidth >= 600.dp
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Categories") },
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
                    Icon(Icons.Filled.Add, contentDescription = "Add Category")
                }
            }
        ) { padding ->
            if (uiState.isLoading) {
                LoadingState(Modifier.padding(padding))
            } else if (isExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    CategoryListPane(
                        categories = uiState.categories,
                        onSelect = viewModel::openEdit,
                        onDelete = { deleteTarget = it },
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.showForm) {
                        CategoryFormPane(
                            name = uiState.form.name,
                            description = uiState.form.description,
                            formError = uiState.form.formError,
                            isEditing = uiState.editingCategory != null,
                            onNameChange = viewModel::onNameChange,
                            onDescriptionChange = viewModel::onDescriptionChange,
                            onSave = viewModel::saveCategory,
                            onClose = viewModel::closeForm,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Box(
                            Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Select a category to edit",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                CategoryListPane(
                    categories = uiState.categories,
                    onSelect = viewModel::openEdit,
                    onDelete = { deleteTarget = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }
            if (!isExpanded && uiState.showForm) {
                ModalBottomSheet(onDismissRequest = viewModel::closeForm) {
                    CategoryFormPane(
                        name = uiState.form.name,
                        description = uiState.form.description,
                        formError = uiState.form.formError,
                        isEditing = uiState.editingCategory != null,
                        onNameChange = viewModel::onNameChange,
                        onDescriptionChange = viewModel::onDescriptionChange,
                        onSave = viewModel::saveCategory,
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
            deleteTarget?.let { cat ->
                ConfirmDialog(
                    title = "Delete Category",
                    message = "Delete \"${cat.name}\"? Products in this category will be unassigned.",
                    confirmText = "Delete",
                    onConfirm = {
                        viewModel.deleteCategory(cat)
                        deleteTarget = null
                    },
                    onDismiss = { deleteTarget = null }
                )
            }
        }
    }
}

@Composable
private fun CategoryListPane(
    categories: List<CategoryEntity>,
    onSelect: (CategoryEntity) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    if (categories.isEmpty()) {
        EmptyState("No categories yet.\nTap + to add one.", modifier)
    } else {
        LazyColumn(
            modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            items(categories, key = { it.id }) { cat ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(cat) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(cat.name, style = MaterialTheme.typography.titleMedium)
                            if (cat.description.isNotBlank()) {
                                Text(
                                    cat.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { onDelete(cat) }) {
                            Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryFormPane(
    name: String,
    description: String,
    formError: String?,
    isEditing: Boolean,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        Text(
            text = if (isEditing) "Edit Category" else "Add Category",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Category name *") },
            singleLine = true,
            isError = formError != null,
            supportingText = formError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text("Description (optional)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(if (isEditing) "Save Changes" else "Add Category")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}