package com.lumipos.app.ui.pos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumipos.app.ui.components.EmptyState
import com.lumipos.app.ui.components.LoadingState
import com.lumipos.app.ui.components.ProductImage
import com.lumipos.app.ui.print.BluetoothPrinterSheet
import com.lumipos.app.ui.util.asCurrency
import com.lumipos.data.schema.CategoryEntity
import com.lumipos.data.schema.CustomerEntity
import com.lumipos.data.schema.ProductEntity
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosScreen(
    viewModel: PosViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showPrinterSheet by remember { mutableStateOf(false) }

    BoxWithConstraints {
        val isExpanded = maxWidth >= 900.dp
        val filtered = uiState.products.filter { p ->
            (uiState.selectedCategoryId == null || p.categoryId == uiState.selectedCategoryId) &&
            (uiState.searchQuery.isBlank() || p.name.contains(uiState.searchQuery, ignoreCase = true))
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Point of Sale") }) },
            bottomBar = {
                if (!isExpanded && uiState.cart.isNotEmpty()) {
                    CompactCartBar(
                        count = uiState.cartItemCount,
                        total = uiState.cartTotal,
                        onOpen = viewModel::showCart
                    )
                }
            }
        ) { padding ->
            if (uiState.isLoading) {
                LoadingState(Modifier.padding(padding))
            } else if (isExpanded) {
                Row(Modifier.fillMaxSize().padding(padding)) {
                    ProductGridPanel(
                        products = filtered,
                        categories = uiState.categories,
                        searchQuery = uiState.searchQuery,
                        onSearchChange = viewModel::onSearchChange,
                        selectedCategoryId = uiState.selectedCategoryId,
                        onCategoryFilterChange = viewModel::onCategoryFilterChange,
                        onAdd = viewModel::addToCart,
                        modifier = Modifier.weight(2f)
                    )
                    CartPanel(
                        cart = uiState.cart,
                        total = uiState.cartTotal,
                        onIncrease = viewModel::increaseQuantity,
                        onDecrease = viewModel::decreaseQuantity,
                        onRemove = viewModel::removeFromCart,
                        onClear = viewModel::clearCart,
                        onCheckout = viewModel::showCheckout,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                ProductGridPanel(
                    products = filtered,
                    categories = uiState.categories,
                    searchQuery = uiState.searchQuery,
                    onSearchChange = viewModel::onSearchChange,
                    selectedCategoryId = uiState.selectedCategoryId,
                    onCategoryFilterChange = viewModel::onCategoryFilterChange,
                    onAdd = viewModel::addToCart,
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            }
        }

        if (!isExpanded && uiState.showCart) {
            ModalBottomSheet(onDismissRequest = viewModel::dismissCart) {
                CartSheet(
                    cart = uiState.cart,
                    total = uiState.cartTotal,
                    onIncrease = viewModel::increaseQuantity,
                    onDecrease = viewModel::decreaseQuantity,
                    onRemove = viewModel::removeFromCart,
                    onClear = viewModel::clearCart,
                    onCheckout = viewModel::showCheckout
                )
            }
        }
        if (uiState.showCheckout) {
            Dialog(
                onDismissRequest = viewModel::dismissCheckout,
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    CheckoutScreen(
                        cart = uiState.cart,
                        total = uiState.cartTotal,
                        changeDue = uiState.changeDue,
                        customers = uiState.customers,
                        paymentMethod = uiState.paymentMethod,
                        selectedCustomerId = uiState.selectedCustomerId,
                        amountPaid = uiState.amountPaid,
                        isPaying = uiState.isPaying,
                        onAmountPaidChange = viewModel::onAmountPaidChange,
                        onPaymentMethodChange = viewModel::onPaymentMethodChange,
                        onCustomerSelected = viewModel::onCustomerSelected,
                        onIncrease = viewModel::increaseQuantity,
                        onDecrease = viewModel::decreaseQuantity,
                        onRemove = viewModel::removeFromCart,
                        onCharge = viewModel::pay,
                        onDismiss = viewModel::dismissCheckout
                    )
                }
            }
        }
        uiState.lastInvoice?.let { invoice ->
            AlertDialog(
                onDismissRequest = viewModel::dismissLastInvoice,
                title = { Text("Payment Successful") },
                text = { Text("Sale $invoice completed.") },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showPrinterSheet = true }) { Text("Print Receipt") }
                        TextButton(onClick = viewModel::dismissLastInvoice) { Text("OK") }
                    }
                }
            )
        }
        uiState.lastReceipt?.let { receipt ->
            if (showPrinterSheet) {
                BluetoothPrinterSheet(
                    payload = receipt,
                    onDismiss = { showPrinterSheet = false }
                )
            }
        }
        uiState.error?.let { error ->
            AlertDialog(
                onDismissRequest = viewModel::dismissError,
                title = { Text("Error") },
                text = { Text(error) },
                confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductGridPanel(
    products: List<ProductEntity>,
    categories: List<CategoryEntity>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedCategoryId: Long?,
    onCategoryFilterChange: (Long?) -> Unit,
    onAdd: (ProductEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                label = { Text("Search products") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            CategoryFilter(
                categories = categories,
                selectedCategoryId = selectedCategoryId,
                onSelected = onCategoryFilterChange
            )
        }
        if (products.isEmpty()) {
            EmptyState("No products found.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(products, key = { it.id }) { p ->
                    ProductTile(
                        product = p,
                        categoryName = categories.find { it.id == p.categoryId }?.name ?: "",
                        onAdd = { onAdd(p) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductTile(
    product: ProductEntity,
    categoryName: String,
    onAdd: () -> Unit
) {
    val outOfStock = product.stock <= 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (outOfStock) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            ProductImage(
                path = product.imageUrl,
                contentDescription = product.name,
                modifier = Modifier.fillMaxWidth().height(64.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                product.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (categoryName.isNotBlank()) {
                Text(
                    categoryName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                product.price.asCurrency(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                if (outOfStock) "Out of stock" else "${product.stock} in stock",
                style = MaterialTheme.typography.bodySmall,
                color = if (outOfStock) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onAdd,
                enabled = !outOfStock,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFilter(
    categories: List<CategoryEntity>,
    selectedCategoryId: Long?,
    onSelected: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = categories.find { it.id == selectedCategoryId }?.name ?: "All"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .widthIn(min = 120.dp, max = 150.dp)
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

@Composable
private fun CheckoutScreen(
    cart: List<CartItem>,
    total: Double,
    changeDue: Double,
    customers: List<CustomerEntity>,
    paymentMethod: String,
    selectedCustomerId: Long?,
    amountPaid: String,
    isPaying: Boolean,
    onAmountPaidChange: (String) -> Unit,
    onPaymentMethodChange: (String) -> Unit,
    onCustomerSelected: (Long?) -> Unit,
    onIncrease: (Long) -> Unit,
    onDecrease: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onCharge: () -> Unit,
    onDismiss: () -> Unit
) {
    val tendered = amountPaid.toDoubleOrNull() ?: 0.0
    val canCharge = !isPaying && tendered >= total
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Checkout",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss, enabled = !isPaying) { Text("Cancel") }
        }
        HorizontalDivider()
        Text(
            "Items (${cart.sumOf { it.quantity }})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        if (cart.isEmpty()) {
            EmptyState("Cart is empty.")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(cart, key = { it.product.id }) { item ->
                    CartRow(
                        item = item,
                        onIncrease = { onIncrease(item.product.id) },
                        onDecrease = { onDecrease(item.product.id) },
                        onRemove = { onRemove(item.product.id) }
                    )
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(vertical = 8.dp)) {
            CustomerDropdown(
                customers = customers,
                selectedCustomerId = selectedCustomerId,
                onSelected = onCustomerSelected
            )
            Spacer(Modifier.height(8.dp))
            PaymentMethodDropdown(method = paymentMethod, onChange = onPaymentMethodChange)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = amountPaid,
                onValueChange = onAmountPaidChange,
                label = { Text("Amount tendered") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.End),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Total", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Change due",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        total.asCurrency(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        if (tendered >= total) changeDue.asCurrency() else "-",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (tendered <= 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onCharge,
                enabled = canCharge,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                if (isPaying) {
                    Text("Processing...")
                } else {
                    Icon(Icons.Filled.ShoppingCart, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Charge")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CartPanel(
    cart: List<CartItem>,
    total: Double,
    onIncrease: (Long) -> Unit,
    onDecrease: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onClear: () -> Unit,
    onCheckout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Cart (${cart.sumOf { it.quantity }})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClear, enabled = cart.isNotEmpty()) { Text("Clear") }
            }
            HorizontalDivider()
            if (cart.isEmpty()) {
                EmptyState("Cart is empty.\nTap a product to add it.")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(cart, key = { it.product.id }) { item ->
                        CartRow(
                            item = item,
                            onIncrease = { onIncrease(item.product.id) },
                            onDecrease = { onDecrease(item.product.id) },
                            onRemove = { onRemove(item.product.id) }
                        )
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text("Total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    total.asCurrency(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Button(
                onClick = onCheckout,
                enabled = cart.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Filled.ShoppingCart, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Checkout")
            }
        }
    }
}

@Composable
private fun CompactCartBar(
    count: Int,
    total: Double,
    onOpen: () -> Unit
) {
    Surface(tonalElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("$count items", style = MaterialTheme.typography.bodySmall)
                Text(total.asCurrency(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Button(onClick = onOpen) { Text("View Cart") }
        }
    }
}

@Composable
private fun CartSheet(
    cart: List<CartItem>,
    total: Double,
    onIncrease: (Long) -> Unit,
    onDecrease: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onClear: () -> Unit,
    onCheckout: () -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Cart (${cart.sumOf { it.quantity }})",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClear, enabled = cart.isNotEmpty()) { Text("Clear") }
        }
        if (cart.isEmpty()) {
            EmptyState("Cart is empty.")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f, fill = false).heightIn(max = 360.dp)
            ) {
                items(cart, key = { it.product.id }) { item ->
                    CartRow(
                        item = item,
                        onIncrease = { onIncrease(item.product.id) },
                        onDecrease = { onDecrease(item.product.id) },
                        onRemove = { onRemove(item.product.id) }
                    )
                }
            }
        }
        CartSummary(cart = cart, total = total)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onCheckout,
            enabled = cart.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Checkout")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CartSummary(cart: List<CartItem>, total: Double) {
    Column {
        cart.take(3).forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    "${item.quantity} x ${item.product.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(item.subtotal.asCurrency(), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (cart.size > 3) {
            Text(
                "+${cart.size - 3} more items",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("Total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                total.asCurrency(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentMethodDropdown(
    method: String,
    onChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val methods = listOf(
        PaymentMethod.CASH,
        PaymentMethod.CARD,
        PaymentMethod.BANK,
        PaymentMethod.OTHER
    )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = method,
            onValueChange = {},
            readOnly = true,
            label = { Text("Payment method") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            methods.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m) },
                    onClick = { onChange(m); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerDropdown(
    customers: List<CustomerEntity>,
    selectedCustomerId: Long?,
    onSelected: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = customers.find { it.id == selectedCustomerId }?.name ?: "Walk-in (no customer)"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Customer") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Walk-in (no customer)") },
                onClick = { onSelected(null); expanded = false }
            )
            customers.forEach { customer ->
                DropdownMenuItem(
                    text = { Text(customer.name) },
                    onClick = { onSelected(customer.id); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun CartRow(
    item: CartItem,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.product.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${item.product.price.asCurrency()} each",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FilledTonalButton(onClick = onDecrease, contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Filled.Remove, "Decrease", Modifier.size(18.dp))
        }
        Text(
            "${item.quantity}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        FilledTonalButton(onClick = onIncrease, contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Filled.Add, "Increase", Modifier.size(18.dp))
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, "Remove", tint = MaterialTheme.colorScheme.error)
        }
        Text(
            item.subtotal.asCurrency(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(90.dp)
        )
    }
}