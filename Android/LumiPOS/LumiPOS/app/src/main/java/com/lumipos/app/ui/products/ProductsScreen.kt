package com.lumipos.app.ui.products

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.layout.ContentScale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lumipos.app.ui.components.ConfirmDialog
import com.lumipos.app.ui.components.EmptyState
import com.lumipos.app.ui.components.LoadingState
import com.lumipos.app.ui.util.asCurrency
import com.lumipos.data.schema.ProductEntity
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductsScreen(
    onBack: (() -> Unit)? = null,
    viewModel: ProductsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<ProductEntity?>(null) }

    BoxWithConstraints {
        val isExpanded = maxWidth >= 600.dp
        val filteredProducts = uiState.products.filter { p ->
            (uiState.selectedCategoryId == null || p.categoryId == uiState.selectedCategoryId) &&
            (uiState.searchQuery.isBlank() || p.name.contains(uiState.searchQuery, ignoreCase = true))
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Products") },
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
                    Icon(Icons.Filled.Add, contentDescription = "Add Product")
                }
            }
        ) { padding ->
            if (uiState.isLoading) {
                LoadingState(Modifier.padding(padding))
            } else if (isExpanded) {
                Row(Modifier.fillMaxSize().padding(padding)) {
                    ProductGridPane(
                        products = filteredProducts,
                        categories = uiState.categories,
                        searchQuery = uiState.searchQuery,
                        onSearchChange = viewModel::onSearchChange,
                        selectedCategoryId = uiState.selectedCategoryId,
                        onCategoryFilterChange = viewModel::onCategoryFilterChange,
                        onSelect = viewModel::openEdit,
                        onDelete = { deleteTarget = it },
                        columns = 3,
                        modifier = Modifier.weight(1.5f)
                    )
                    if (uiState.showForm) {
                        ProductFormPane(
                            form = uiState.form,
                            categories = uiState.categories,
                            isEditing = uiState.editingProduct != null,
                            onNameChange = viewModel::onNameChange,
                            onSkuChange = viewModel::onSkuChange,
                            onCategoryIdChange = viewModel::onCategoryIdChange,
                            onPriceChange = viewModel::onPriceChange,
                            onCostChange = viewModel::onCostChange,
                            onStockChange = viewModel::onStockChange,
                            onImagePicked = viewModel::onImagePicked,
                            onClearImage = viewModel::clearImage,
                            onSave = viewModel::saveProduct,
                            onClose = viewModel::closeForm,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text("Select a product to edit", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                ProductGridPane(
                    products = filteredProducts,
                    categories = uiState.categories,
                    searchQuery = uiState.searchQuery,
                    onSearchChange = viewModel::onSearchChange,
                    selectedCategoryId = uiState.selectedCategoryId,
                    onCategoryFilterChange = viewModel::onCategoryFilterChange,
                    onSelect = viewModel::openEdit,
                    onDelete = { deleteTarget = it },
                    columns = 2,
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            }
            if (!isExpanded && uiState.showForm) {
                ModalBottomSheet(onDismissRequest = viewModel::closeForm) {
                    ProductFormPane(
                        form = uiState.form,
                        categories = uiState.categories,
                        isEditing = uiState.editingProduct != null,
                        onNameChange = viewModel::onNameChange,
                        onSkuChange = viewModel::onSkuChange,
                        onCategoryIdChange = viewModel::onCategoryIdChange,
                        onPriceChange = viewModel::onPriceChange,
                        onCostChange = viewModel::onCostChange,
                        onStockChange = viewModel::onStockChange,
                        onImagePicked = viewModel::onImagePicked,
                        onClearImage = viewModel::clearImage,
                        onSave = viewModel::saveProduct,
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
            deleteTarget?.let { p ->
                ConfirmDialog(
                    title = "Delete Product",
                    message = "Delete \"${p.name}\"?",
                    confirmText = "Delete",
                    onConfirm = { viewModel.deleteProduct(p); deleteTarget = null },
                    onDismiss = { deleteTarget = null }
                )
            }
        }
    }
}

@Composable
private fun ProductGridPane(
    products: List<ProductEntity>,
    categories: List<com.lumipos.data.schema.CategoryEntity>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedCategoryId: Long?,
    onCategoryFilterChange: (Long?) -> Unit,
    onSelect: (ProductEntity) -> Unit,
    onDelete: (ProductEntity) -> Unit,
    columns: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                label = { Text("Search") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            CategoryFilterDropdown(
                categories = categories,
                selectedCategoryId = selectedCategoryId,
                onSelected = onCategoryFilterChange
            )
        }
        if (products.isEmpty()) {
            EmptyState("No products yet.\nTap + to add one.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(12.dp)
            ) {
                items(products, key = { it.id }) { p ->
                    val catName = categories.find { it.id == p.categoryId }?.name
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        onClick = { onSelect(p) }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            if (!p.imageUrl.isNullOrBlank()) {
AsyncImage(
                                    model = File(p.imageUrl),
                                    contentDescription = p.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().height(72.dp)
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            Text(
                                p.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!catName.isNullOrBlank()) {
                                Text(
                                    catName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                p.price.asCurrency(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                "Stock: ${p.stock}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (p.stock <= 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFilterDropdown(
    categories: List<com.lumipos.data.schema.CategoryEntity>,
    selectedCategoryId: Long?,
    onSelected: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = categories.find { it.id == selectedCategoryId }?.name ?: "All"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All categories") },
                onClick = { onSelected(null); expanded = false }
            )
            categories.forEach { cat ->
                DropdownMenuItem(
                    text = { Text(cat.name) },
                    onClick = { onSelected(cat.id); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductFormPane(
    form: ProductFormState,
    categories: List<com.lumipos.data.schema.CategoryEntity>,
    isEditing: Boolean,
    onNameChange: (String) -> Unit,
    onSkuChange: (String) -> Unit,
    onCategoryIdChange: (Long?) -> Unit,
    onPriceChange: (String) -> Unit,
    onCostChange: (String) -> Unit,
    onStockChange: (String) -> Unit,
    onImagePicked: (android.net.Uri) -> Unit,
    onClearImage: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onImagePicked(uri) }

    Column(modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
        Text(
            text = if (isEditing) "Edit Product" else "Add Product",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = form.name,
            onValueChange = onNameChange,
            label = { Text("Product name *") },
            singleLine = true,
            isError = form.formError != null,
            supportingText = form.formError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = form.sku,
            onValueChange = onSkuChange,
            label = { Text("SKU *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))

        var catExpanded by remember { mutableStateOf(false) }
        val catName = categories.find { it.id == form.categoryId }?.name ?: "None"
        ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = it }) {
            OutlinedTextField(
                value = catName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Category") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = { onCategoryIdChange(null); catExpanded = false }
                )
                categories.forEach { cat ->
                    DropdownMenuItem(
                        text = { Text(cat.name) },
                        onClick = { onCategoryIdChange(cat.id); catExpanded = false }
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.price,
                onValueChange = onPriceChange,
                label = { Text("Price") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = form.cost,
                onValueChange = onCostChange,
                label = { Text("Cost") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = form.stock,
            onValueChange = onStockChange,
            label = { Text("Stock") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Text("Photo", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().height(120.dp)
        ) {
            if (form.imageUrl.isBlank()) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Inventory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "No photo selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                AsyncImage(
                    model = File(form.imageUrl),
                    contentDescription = "Product photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Choose photo")
            }
            if (form.imageUrl.isNotBlank()) {
                OutlinedButton(onClick = onClearImage, modifier = Modifier.weight(1f)) {
                    Text("Remove")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(if (isEditing) "Save Changes" else "Add Product")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}