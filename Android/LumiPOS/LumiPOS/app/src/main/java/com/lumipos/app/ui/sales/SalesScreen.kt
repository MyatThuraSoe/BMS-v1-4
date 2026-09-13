package com.lumipos.app.ui.sales

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumipos.app.ui.components.EmptyState
import com.lumipos.app.ui.components.LoadingState
import com.lumipos.app.ui.print.BluetoothPrinterSheet
import com.lumipos.app.ui.util.asCurrency
import com.lumipos.app.ui.util.asDateTime
import com.lumipos.data.schema.SaleEntity

private val statusOptions = listOf(null, "PAID", "PARTIAL", "UNPAID")

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SalesScreen(
    viewModel: SalesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sales") }) }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingState(Modifier.padding(padding))
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SaleRange.entries.forEach { range ->
                        FilterChip(
                            selected = uiState.dateRange == range,
                            onClick = { viewModel.setDateRange(range) },
                            label = { Text(range.label) }
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    statusOptions.forEach { status ->
                        FilterChip(
                            selected = uiState.statusFilter == status,
                            onClick = { viewModel.setStatusFilter(status) },
                            label = { Text(status ?: "All") }
                        )
                    }
                }
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::onSearchChange,
                    label = { Text("Search by invoice #") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
                val query = uiState.searchQuery.trim()
                val filtered = uiState.sales.filter {
                    (uiState.statusFilter == null || it.paymentStatus == uiState.statusFilter) &&
                        (query.isEmpty() || it.invoiceNumber.contains(query, ignoreCase = true))
                }
                if (filtered.isEmpty()) {
                    EmptyState("No sales in this range.")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(filtered, key = { it.id }) { sale ->
                            SaleCard(sale = sale, onClick = { viewModel.selectSale(sale) })
                        }
                    }
                }
            }
        }
    }

    uiState.selectedSale?.let { sale ->
        SaleDetailDialog(
            sale = sale,
            viewModel = viewModel
        )
    }

    if (uiState.showReturnDialog && uiState.selectedSale != null) {
        ReturnDialog(
            uiState = uiState,
            viewModel = viewModel
        )
    }

    if (uiState.showVoidDialog) {
        VoidDialog(
            uiState = uiState,
            viewModel = viewModel
        )
    }

    uiState.printReceipt?.let { receipt ->
        BluetoothPrinterSheet(payload = receipt, onDismiss = viewModel::dismissPrintReceipt)
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
private fun SaleCard(
    sale: SaleEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sale.invoiceNumber, style = MaterialTheme.typography.titleMedium)
                Text(sale.saleDate.asDateTime(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                PaymentStatusChip(status = sale.paymentStatus, voided = sale.isVoided, returned = sale.returnStatus)
                Spacer(Modifier.height(4.dp))
                Text(sale.total.asCurrency(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PaymentStatusChip(status: String, voided: Boolean, returned: String) {
    val color = when {
        voided -> MaterialTheme.colorScheme.error
        returned == "FULL" -> MaterialTheme.colorScheme.outline
        returned == "PARTIAL" -> MaterialTheme.colorScheme.tertiary
        status == "PAID" -> MaterialTheme.colorScheme.primary
        status == "PARTIAL" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    val label = when {
        voided -> "Voided"
        returned != "COMPLETED" && returned != "NONE" -> returned.replaceFirstChar { c -> c.uppercaseChar() }
        else -> status
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        leadingIcon = { Box(Modifier.padding(start = 4.dp)) { Text("\u2022", color = color, fontWeight = FontWeight.Bold) } }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SaleDetailDialog(
    sale: SaleEntity,
    viewModel: SalesViewModel
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    AlertDialog(
        onDismissRequest = viewModel::dismissDetail,
        title = {
            Column {
                Text(sale.invoiceNumber, style = MaterialTheme.typography.titleMedium)
                Text(sale.saleDate.asDateTime(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) { Text("Customer", Modifier.weight(1f)); Text(uiState.customerName ?: "Walk-in") }
                Row(Modifier.fillMaxWidth()) { Text("Method", Modifier.weight(1f)); Text(sale.paymentMethod) }
                Row(Modifier.fillMaxWidth()) { Text("Payment", Modifier.weight(1f)); Text(sale.paymentStatus) }
                Spacer(Modifier.height(8.dp))
                if (uiState.saleItems.isEmpty()) {
                    Text("No items for this sale.", style = MaterialTheme.typography.bodySmall)
                } else {
                    uiState.saleItems.forEach { item ->
                        Column(Modifier.padding(vertical = 2.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    uiState.productNames[item.productId] ?: "Product #${item.productId}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(item.totalPrice.asCurrency(), style = MaterialTheme.typography.bodyMedium)
                            }
                            if (item.quantityRefunded > 0) {
                                Text(
                                    "${item.quantityRefunded} returned",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth()) { Text("Subtotal", Modifier.weight(1f)); Text(sale.subtotal.asCurrency()) }
                Row(Modifier.fillMaxWidth()) { Text("Tax", Modifier.weight(1f)); Text(sale.tax.asCurrency()) }
                Row(Modifier.fillMaxWidth()) { Text("Discount", Modifier.weight(1f)); Text(sale.discount.asCurrency()) }
                Row(Modifier.fillMaxWidth()) {
                    Text("Total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(sale.total.asCurrency(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                if (sale.isVoided) {
                    Spacer(Modifier.height(8.dp))
                    Text("Voided: ${sale.voidedReason.orEmpty()}", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::printSelected, enabled = uiState.saleItems.isNotEmpty()) { Text("Print") }
                if (!sale.isVoided) {
                    Button(onClick = viewModel::openReturnDialog) { Text("Return") }
                }
            }
        },
        dismissButton = {
            Row {
                if (!sale.isVoided) {
                    OutlinedButton(onClick = viewModel::openVoidDialog) { Text("Void Sale") }
                }
                TextButton(onClick = viewModel::dismissDetail) { Text("Close") }
            }
        }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReturnDialog(
    uiState: SalesUiState,
    viewModel: SalesViewModel
) {
    AlertDialog(
        onDismissRequest = { if (!uiState.isSubmitting) viewModel.dismissReturnDialog() },
        title = { Text("Record Return") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                uiState.saleItems.forEach { item ->
                    val maxReturnable = item.quantity - item.quantityRefunded
                    if (maxReturnable > 0) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    uiState.productNames[item.productId] ?: "Product #${item.productId}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "Returnable: $maxReturnable (${item.unitPrice.asCurrency()} each)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { viewModel.updateReturnQuantity(item.id, -1) }, enabled = (uiState.returnQuantities[item.id] ?: 0) > 0 && !uiState.isSubmitting) {
                                Icon(Icons.Filled.Remove, null)
                            }
                            Text(
                                (uiState.returnQuantities[item.id] ?: 0).toString(),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            IconButton(
                                onClick = { viewModel.updateReturnQuantity(item.id, 1) },
                                enabled = (uiState.returnQuantities[item.id] ?: 0) < maxReturnable && !uiState.isSubmitting
                            ) {
                                Icon(Icons.Filled.Add, null)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.returnReason,
                    onValueChange = viewModel::setReturnReason,
                    label = { Text("Reason (optional)") },
                    singleLine = true,
                    enabled = !uiState.isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("Refund total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(uiState.returnTotal.asCurrency(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::submitReturn,
                enabled = uiState.returnQuantities.any { (_, qty) -> qty > 0 } && !uiState.isSubmitting
            ) { Text(if (uiState.isSubmitting) "Saving..." else "Record") }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissReturnDialog, enabled = !uiState.isSubmitting) { Text("Cancel") }
        }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun VoidDialog(
    uiState: SalesUiState,
    viewModel: SalesViewModel
) {
    AlertDialog(
        onDismissRequest = { if (!uiState.isSubmitting) viewModel.dismissVoidDialog() },
        title = { Text("Void Sale") },
        text = {
            Column {
                Text("This will reverse the sale, restore stock and undo any customer credit. This action cannot be undone.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.voidReason,
                    onValueChange = viewModel::setVoidReason,
                    label = { Text("Reason *") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    enabled = !uiState.isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::submitVoid,
                enabled = uiState.voidReason.isNotBlank() && !uiState.isSubmitting
            ) { Text(if (uiState.isSubmitting) "Voiding..." else "Void") }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissVoidDialog, enabled = !uiState.isSubmitting) { Text("Cancel") }
        }
    )
}