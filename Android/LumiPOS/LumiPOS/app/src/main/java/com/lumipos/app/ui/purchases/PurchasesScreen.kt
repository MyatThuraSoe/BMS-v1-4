package com.lumipos.app.ui.purchases

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumipos.app.ui.components.EmptyState
import com.lumipos.app.ui.components.LoadingState
import com.lumipos.app.ui.util.asCurrency
import com.lumipos.app.ui.util.asDateTime
import com.lumipos.data.schema.PurchaseEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchasesScreen(
    onBack: (() -> Unit)? = null,
    viewModel: PurchasesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showProductPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Purchases") },
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
            FloatingActionButton(onClick = viewModel::openForm) {
                Icon(Icons.Filled.Add, contentDescription = "New Purchase")
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingState(Modifier.padding(padding))
        } else if (uiState.purchases.isEmpty()) {
            EmptyState("No purchases yet.\nTap + to record one.", Modifier.padding(padding))
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.padding(padding).fillMaxSize()
            ) {
                items(uiState.purchases, key = { it.id }) { purchase ->
                    PurchaseCard(purchase = purchase, supplierName = uiState.supplierNames[purchase.supplierId] ?: "Supplier #${purchase.supplierId}", onClick = { viewModel.selectPurchase(purchase) })
                }
            }
        }
    }

    if (uiState.showForm) {
        PurchaseFormSheet(uiState = uiState, viewModel = viewModel, onShowProductPicker = { showProductPicker = true })
    }

    if (showProductPicker) {
        ProductPickerDialog(uiState = uiState, viewModel = viewModel, onDismiss = { showProductPicker = false })
    }

    uiState.selectedPurchase?.let { purchase ->
        PurchaseDetailDialog(purchase = purchase, uiState = uiState, viewModel = viewModel)
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
private fun PurchaseCard(
    purchase: PurchaseEntity,
    supplierName: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(purchase.purchaseNumber, style = MaterialTheme.typography.titleMedium)
                Text(supplierName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(purchase.purchaseDate.asDateTime(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                AssistChip(onClick = {}, label = { Text(purchase.paymentStatus) })
                Spacer(Modifier.height(4.dp))
                Text(purchase.total.asCurrency(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchaseFormSheet(
    uiState: PurchasesUiState,
    viewModel: PurchasesViewModel,
    onShowProductPicker: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = viewModel::dismissForm) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 24.dp).fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            Text("New Purchase", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            // Supplier picker
            Text("Supplier", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var showSupplierPicker by remember { mutableStateOf(false) }
            Text(
                uiState.supplierNames[uiState.selectedSupplierId] ?: "Select supplier",
                modifier = Modifier.fillMaxWidth().clickable { showSupplierPicker = true }.padding(12.dp)
            )
            if (showSupplierPicker) {
                AlertDialog(
                    onDismissRequest = { showSupplierPicker = false },
                    confirmButton = {},
                    text = {
                        Column {
                            uiState.suppliers.forEach { supplier ->
                                ListItem(
                                    headlineContent = { Text(supplier.name) },
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        viewModel.selectSupplier(supplier.id)
                                        showSupplierPicker = false
                                    }
                                )
                            }
                        }
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            // Line items
            for (line in uiState.lines) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(line.product.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.removeLine(line.product.id) }) {
                                Icon(Icons.Filled.Close, "Remove")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = line.quantity,
                                onValueChange = { viewModel.updateQuantity(line.product.id, it) },
                                label = { Text("Qty") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = line.unitCost,
                                onValueChange = { viewModel.updateUnitCost(line.product.id, it) },
                                label = { Text("Unit cost") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            Text(line.lineTotal.asCurrency(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterVertically))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = onShowProductPicker, modifier = Modifier.fillMaxWidth()) {
                Text("Add Product")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = uiState.taxRate,
                onValueChange = viewModel::onTaxRateChange,
                label = { Text("Tax rate %") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(Modifier.fillMaxWidth()) { Text("Subtotal", Modifier.weight(1f)); Text(uiState.subtotal.asCurrency()) }
            Row(Modifier.fillMaxWidth()) { Text("Tax", Modifier.weight(1f)); Text(uiState.tax.asCurrency()) }
            Row(Modifier.fillMaxWidth()) {
                Text("Total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(uiState.total.asCurrency(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = viewModel::submitPurchase,
                enabled = uiState.selectedSupplierId != null && uiState.lines.any { it.qty > 0 } && !uiState.isSubmitting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (uiState.isSubmitting) "Saving..." else "Save Purchase")
            }
        }
    }
}

@Composable
private fun ProductPickerDialog(
    uiState: PurchasesUiState,
    viewModel: PurchasesViewModel,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Product") },
        text = {
            if (uiState.products.isEmpty()) {
                Text("No active products.")
            } else {
                Column {
                    uiState.products.forEach { product ->
                        ListItem(
                            headlineContent = { Text(product.name) },
                            supportingContent = { Text("Stock: ${product.stock} | Cost: ${product.cost.asCurrency()}") },
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.addProduct(product)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun PurchaseDetailDialog(
    purchase: PurchaseEntity,
    uiState: PurchasesUiState,
    viewModel: PurchasesViewModel
) {
    AlertDialog(
        onDismissRequest = viewModel::dismissDetail,
        title = {
            Column {
                Text(purchase.purchaseNumber, style = MaterialTheme.typography.titleMedium)
                Text(purchase.purchaseDate.asDateTime(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column {
                Row(Modifier.fillMaxWidth()) { Text("Supplier", Modifier.weight(1f)); Text(uiState.supplierNames[purchase.supplierId] ?: "-") }
                Row(Modifier.fillMaxWidth()) { Text("Status", Modifier.weight(1f)); Text(purchase.paymentStatus) }
                Spacer(Modifier.height(8.dp))
                if (uiState.purchaseItems.isEmpty()) {
                    Text("No items.", style = MaterialTheme.typography.bodySmall)
                } else {
                    uiState.purchaseItems.forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                "${item.quantity} x ${uiState.productNames[item.productId] ?: "Product #${item.productId}"}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(item.totalCost.asCurrency(), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth()) { Text("Subtotal", Modifier.weight(1f)); Text(purchase.subtotal.asCurrency()) }
                Row(Modifier.fillMaxWidth()) { Text("Tax", Modifier.weight(1f)); Text(purchase.tax.asCurrency()) }
                Row(Modifier.fillMaxWidth()) {
                    Text("Total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(purchase.total.asCurrency(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            if (purchase.paymentStatus != "PAID") {
                Button(onClick = { viewModel.markPaid(purchase) }) { Text("Mark Paid") }
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissDetail) { Text("Close") }
        }
    )
}