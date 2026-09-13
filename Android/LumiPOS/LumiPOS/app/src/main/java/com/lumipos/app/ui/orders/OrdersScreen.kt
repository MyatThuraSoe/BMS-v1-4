package com.lumipos.app.ui.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumipos.app.ui.components.EmptyState
import com.lumipos.app.ui.components.LoadingState
import com.lumipos.app.ui.print.BluetoothPrinterSheet
import com.lumipos.app.ui.util.asCurrency
import com.lumipos.app.ui.util.asDateTime
import com.lumipos.data.schema.OrderEntity
import com.lumipos.domain.usecase.order.OrderStatus

private val statusOptions = listOf(
    null, OrderStatus.PENDING, OrderStatus.IN_PROGRESS,
    OrderStatus.READY, OrderStatus.COMPLETED, OrderStatus.CANCELLED
)

private val paymentMethods = listOf("CASH", "CARD", "MOBILE", "OTHER")

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    viewModel: OrdersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Orders") }) }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingState(Modifier.padding(padding))
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OrderRange.entries.forEach { range ->
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
                            label = { Text(status?.let { humanize(it) } ?: "All") }
                        )
                    }
                }
                val filtered = uiState.orders.filter {
                    uiState.statusFilter == null || it.status == uiState.statusFilter
                }
                if (filtered.isEmpty()) {
                    EmptyState("No orders here yet.")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(filtered, key = { it.id }) { order ->
                            OrderCard(order = order, onClick = { viewModel.selectOrder(order) })
                        }
                    }
                }
            }
        }
    }

    uiState.selectedOrder?.let { order ->
        val converted = order.convertedSaleId != null
        AlertDialog(
            onDismissRequest = viewModel::dismissDetail,
            title = {
                Column {
                    Text(order.orderNumber, style = MaterialTheme.typography.titleMedium)
                    Text(order.createdAt.asDateTime(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            text = {
                Column {
                    Text("Status: ${humanize(order.status)}", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    if (uiState.orderItems.isEmpty()) {
                        Text("No items for this order.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        uiState.orderItems.forEach { item ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(
                                    "${item.quantity} x Product #${item.productId}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(item.subtotal.asCurrency(), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("Total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(order.totalAmount.asCurrency(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::printSelected,
                        enabled = uiState.orderItems.isNotEmpty()
                    ) { Text("Print") }
                    if (order.status == OrderStatus.READY && !converted) {
                        Button(onClick = viewModel::openConvertDialog) { Text("Convert to Sale") }
                    }
                    if (order.status == OrderStatus.PENDING || order.status == OrderStatus.IN_PROGRESS) {
                        Button(onClick = {
                            viewModel.markStatus(
                                order,
                                if (order.status == OrderStatus.PENDING) OrderStatus.IN_PROGRESS else OrderStatus.READY
                            )
                        }) { Text(if (order.status == OrderStatus.PENDING) "Start" else "Ready") }
                    }
                }
            },
            dismissButton = {
                Row {
                    if (order.status != OrderStatus.COMPLETED && order.status != OrderStatus.CANCELLED) {
                        OutlinedButton(onClick = { viewModel.cancelOrder(order, null) }) {
                            Text("Cancel Order")
                        }
                    }
                    TextButton(onClick = viewModel::dismissDetail) { Text("Close") }
                }
            }
        )
    }

    if (uiState.showConvertDialog) {
        val order = uiState.selectedOrder
        if (order != null) {
            AlertDialog(
                onDismissRequest = viewModel::dismissConvertDialog,
                title = { Text("Convert to Sale") },
                text = {
                    Column {
                        Text("Order ${order.orderNumber} - ${order.totalAmount.asCurrency()}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = uiState.amountPaid,
                            onValueChange = viewModel::setAmountPaid,
                            label = { Text("Amount Paid") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            paymentMethods.forEach { method ->
                                FilterChip(
                                    selected = uiState.paymentMethod == method,
                                    onClick = { viewModel.setPaymentMethod(method) },
                                    label = { Text(method) }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = viewModel::convertToSale) { Text("Complete & Pay") }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissConvertDialog) { Text("Back") }
                }
            )
        }
    }

    uiState.printReceipt?.let { receipt ->
        BluetoothPrinterSheet(
            payload = receipt,
            onDismiss = viewModel::dismissPrintReceipt
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
private fun OrderCard(
    order: OrderEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(order.orderNumber, style = MaterialTheme.typography.titleMedium)
                Text(order.createdAt.asDateTime(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                StatusChip(status = order.status)
                Spacer(Modifier.height(4.dp))
                Text(order.totalAmount.asCurrency(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val color = when (status) {
        OrderStatus.CANCELLED -> MaterialTheme.colorScheme.outline
        OrderStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        OrderStatus.IN_PROGRESS -> MaterialTheme.colorScheme.tertiary
        OrderStatus.READY -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = {},
        label = { Text(humanize(status)) },
        leadingIcon = { Box(Modifier.padding(start = 4.dp)) { Text("\u2022", color = color, fontWeight = FontWeight.Bold) } }
    )
}

private fun humanize(status: String): String = when (status) {
    OrderStatus.PENDING -> "Pending"
    OrderStatus.IN_PROGRESS -> "In Progress"
    OrderStatus.READY -> "Ready"
    OrderStatus.COMPLETED -> "Completed"
    OrderStatus.CANCELLED -> "Cancelled"
    else -> status
}