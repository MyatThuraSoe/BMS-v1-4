package com.lumipos.app.ui.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lumipos.app.ui.balances.CustomerBalancesScreen
import com.lumipos.app.ui.categories.CategoriesScreen
import com.lumipos.app.ui.customers.CustomersScreen
import com.lumipos.app.ui.products.ProductsScreen
import com.lumipos.app.ui.purchases.PurchasesScreen
import com.lumipos.app.ui.stock.StockControlScreen
import com.lumipos.app.ui.suppliers.SuppliersScreen

private enum class InventorySection { HOME, PRODUCTS, CATEGORIES, CUSTOMERS, BALANCES, SUPPLIERS, PURCHASES, STOCK }

private data class InventoryEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val section: InventorySection
)

private val inventoryEntries = listOf(
    InventoryEntry("Products", "Manage catalog, prices, stock levels", Icons.Filled.Inventory2, InventorySection.PRODUCTS),
    InventoryEntry("Categories", "Group products for faster selling", Icons.Filled.Category, InventorySection.CATEGORIES),
    InventoryEntry("Customers", "Customer directory and balances", Icons.Filled.Group, InventorySection.CUSTOMERS),
    InventoryEntry("Customer Balances", "Track AR and record payments", Icons.Filled.Payments, InventorySection.BALANCES),
    InventoryEntry("Suppliers", "Vendor list for purchasing", Icons.Filled.LocalShipping, InventorySection.SUPPLIERS),
    InventoryEntry("Purchases", "Record incoming stock from suppliers", Icons.Filled.ShoppingCart, InventorySection.PURCHASES),
    InventoryEntry("Stock Control", "Adjust stock, low-stock alerts, history", Icons.Filled.Badge, InventorySection.STOCK)
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InventoryHubScreen() {
    var sectionName by rememberSaveable { mutableStateOf(InventorySection.HOME.name) }
    val section = InventorySection.entries.firstOrNull { it.name == sectionName } ?: InventorySection.HOME

    when (section) {
        InventorySection.HOME -> InventoryHome(onOpen = { sectionName = it.name })
        InventorySection.PRODUCTS -> ProductsScreen(onBack = { sectionName = InventorySection.HOME.name })
        InventorySection.CATEGORIES -> CategoriesScreen(onBack = { sectionName = InventorySection.HOME.name })
        InventorySection.CUSTOMERS -> CustomersScreen(onBack = { sectionName = InventorySection.HOME.name })
        InventorySection.BALANCES -> CustomerBalancesScreen(onBack = { sectionName = InventorySection.HOME.name })
        InventorySection.SUPPLIERS -> SuppliersScreen(onBack = { sectionName = InventorySection.HOME.name })
        InventorySection.PURCHASES -> PurchasesScreen(onBack = { sectionName = InventorySection.HOME.name })
        InventorySection.STOCK -> StockControlScreen(onBack = { sectionName = InventorySection.HOME.name })
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun InventoryHome(onOpen: (InventorySection) -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Inventory") }) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                LazyColumn {
                    items(inventoryEntries) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.title, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = { Text(entry.subtitle) },
                            leadingContent = {
                                Icon(entry.icon, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(entry.section) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}