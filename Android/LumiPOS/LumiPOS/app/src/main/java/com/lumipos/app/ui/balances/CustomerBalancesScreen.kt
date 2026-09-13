package com.lumipos.app.ui.balances

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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumipos.app.ui.components.EmptyState
import com.lumipos.app.ui.components.LoadingState
import com.lumipos.app.ui.util.asCurrency
import com.lumipos.app.ui.util.asDateTime
import com.lumipos.data.schema.CustomerEntity

private val paymentMethods = listOf("CASH", "CARD", "MOBILE", "OTHER")

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CustomerBalancesScreen(
    onBack: () -> Unit,
    viewModel: CustomerBalancesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Customer Balances") },
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
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Total Outstanding", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(uiState.totalOutstanding.asCurrency(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
                if (uiState.customers.isEmpty()) {
                    EmptyState("No customers yet.")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(uiState.customers, key = { it.id }) { customer ->
                            CustomerBalanceCard(
                                customer = customer,
                                onSelect = { viewModel.selectCustomer(customer) },
                                onPay = { viewModel.openPaymentDialog(customer) }
                            )
                        }
                    }
                }
            }
        }
    }

    uiState.selectedCustomer?.let { customer ->
        val payments = uiState.payments
        AlertDialog(
            onDismissRequest = viewModel::dismissCustomer,
            title = { Text(customer.name) },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        Text("Current Balance", modifier = Modifier.weight(1f))
                        Text(customer.currentBalance.asCurrency(), fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text("Credit Limit", modifier = Modifier.weight(1f))
                        Text(customer.creditLimit.asCurrency())
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text("Total Spent", modifier = Modifier.weight(1f))
                        Text(customer.totalSpent.asCurrency())
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Payments", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (payments.isEmpty()) {
                        Text("No payments recorded.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        payments.take(5).forEach { payment ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(
                                    "${payment.paymentMethod} · ${payment.paymentDate.asDateTime()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(payment.amount.asCurrency(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.openPaymentDialog(customer) }) { Text("Record Payment") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCustomer) { Text("Close") }
            }
        )
    }

    if (uiState.showPaymentDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPaymentDialog,
            title = { Text("Record Payment") },
            text = {
                Column {
                    OutlinedTextField(
                        value = uiState.amount,
                        onValueChange = viewModel::setAmount,
                        label = { Text("Amount") },
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.reference,
                        onValueChange = viewModel::setReference,
                        label = { Text("Reference (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.notes,
                        onValueChange = viewModel::setNotes,
                        label = { Text("Notes (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = viewModel::recordPayment, enabled = !uiState.isSaving) {
                    Text(if (uiState.isSaving) "Saving..." else "Save Payment")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPaymentDialog) { Text("Cancel") }
            }
        )
    }

    uiState.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
            title = { Text("Success") },
            text = { Text(message) }
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
private fun CustomerBalanceCard(
    customer: CustomerEntity,
    onSelect: () -> Unit,
    onPay: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(customer.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Balance: ${customer.currentBalance.asCurrency()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (customer.currentBalance > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onPay) { Text("Record Payment") }
            }
        }
    }
}