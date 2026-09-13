package com.lumipos.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StoreInfoScreen(
    onBack: (() -> Unit)? = null,
    viewModel: StoreInfoViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Store Info") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        val shop = uiState.shop
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            StoreCard(
                title = shop?.shopName?.ifBlank { "My Shop" } ?: "My Shop",
                icon = Icons.Filled.Storefront
            ) {
                StoreRow("Shop type", shop?.shopType ?: "OTHER")
                StoreRow("Address", shop?.address?.ifBlank { "Not set" } ?: "Not set")
                StoreRow("Phone", shop?.phone?.ifBlank { "Not set" } ?: "Not set")
                StoreRow("Email", shop?.email?.ifBlank { "Not set" } ?: "Not set")
                StoreRow("Currency", (shop?.currency?.ifBlank { "USD" } ?: "USD"))
            }

            StoreCard(
                title = "Tax & Pricing",
                icon = Icons.Filled.Percent,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                StoreRow(
                    "Tax rate",
                    "${(shop?.taxPercentage ?: 0.0).toInt()}%"
                )
                StoreRow("Tax ID", shop?.taxId?.ifBlank { "Not set" } ?: "Not set")
                StoreRow(
                    "Discounts",
                    if (shop?.discountEnabled == true) "Enabled" else "Disabled"
                )
            }

            StoreCard(
                title = "Payment",
                icon = Icons.Filled.Payments,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                StoreRow("Currency symbol", (shop?.currency ?: "USD"))
                StoreRow("Default price display", "Tax inclusive")
            }
        }
    }
}

@Composable
private fun StoreCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Column(Modifier.padding(top = 8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun StoreRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}