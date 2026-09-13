package com.lumipos.app.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumipos.app.ui.audit.AuditLogsScreen
import com.lumipos.app.ui.backup.BackupScreen
import com.lumipos.app.ui.balances.CustomerBalancesScreen
import com.lumipos.app.ui.categories.CategoriesScreen
import com.lumipos.app.ui.customers.CustomersScreen
import com.lumipos.app.ui.dashboard.DashboardScreen
import com.lumipos.app.ui.expenses.ExpensesScreen
import com.lumipos.app.ui.inventory.InventoryHubScreen
import com.lumipos.app.ui.orders.OrdersScreen
import com.lumipos.app.ui.pos.PosScreen
import com.lumipos.app.ui.products.ProductsScreen
import com.lumipos.app.ui.purchases.PurchasesScreen
import com.lumipos.app.ui.receipts.ReceiptCustomizationScreen
import com.lumipos.app.ui.reports.ReportsScreen
import com.lumipos.app.ui.sales.SalesScreen
import com.lumipos.app.ui.settings.SettingsHubScreen
import com.lumipos.app.ui.settings.StoreInfoScreen
import com.lumipos.app.ui.shifts.ShiftsScreen
import com.lumipos.app.ui.stock.StockControlScreen
import com.lumipos.app.ui.suppliers.SuppliersScreen
import com.lumipos.app.ui.users.UsersScreen
import com.lumipos.app.ui.about.AboutScreen

private val MainTabIcons: Map<MainTab, ImageVector> = mapOf(
    MainTab.POS to Icons.Filled.PointOfSale,
    MainTab.Sales to Icons.Filled.Receipt,
    MainTab.Orders to Icons.AutoMirrored.Filled.ListAlt,
    MainTab.Inventory to Icons.Filled.Warehouse,
    MainTab.Reports to Icons.Filled.Assessment
)

private const val EXPANDED_WIDTH_DP = 600

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumiPOSAppScaffold(
    uiState: MainUiState,
    onTabSelected: (MainTab) -> Unit,
    onSelectOverlay: (MainOverlayPage) -> Unit,
    onCloseOverlay: () -> Unit,
    onToggleDrawer: () -> Unit,
    onCloseDrawer: () -> Unit
) {
    val applyAction: (MenuAction) -> Unit = { action ->
        when (action) {
            is MenuAction.Tab -> onTabSelected(action.tab)
            is MenuAction.Page -> onSelectOverlay(action.page)
        }
    }

    BackHandler(enabled = uiState.drawerOpen) { onCloseDrawer() }
    BackHandler(enabled = !uiState.drawerOpen && uiState.overlayPage != null) { onCloseOverlay() }
    BackHandler(
        enabled = !uiState.drawerOpen && uiState.overlayPage == null && uiState.selectedTab != MainTab.POS
    ) {
        onTabSelected(MainTab.POS)
    }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val isExpanded = maxWidth >= EXPANDED_WIDTH_DP.dp
            Scaffold(
                bottomBar = {
                    if (!isExpanded) {
                        LumiNavigationBar(
                            selectedTab = uiState.selectedTab,
                            onTabSelected = onTabSelected,
                            onOpenMenu = onToggleDrawer
                        )
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                ) {
                    if (isExpanded) {
                        LumiLeftRail(
                            selectedTab = uiState.selectedTab,
                            onTabSelected = onTabSelected
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                    ) {
                        MainContent(
                            uiState = uiState,
                            onTabSelected = onTabSelected,
                            onCloseOverlay = onCloseOverlay,
                            onOpenStock = { onSelectOverlay(MainOverlayPage.Stock) }
                        )
                    }
                    if (isExpanded) {
                        LumiMenuRail(
                            selectedPage = uiState.overlayPage,
                            onOpenMenu = onToggleDrawer,
                            onSelect = applyAction
                        )
                    }
                }
            }
        }

        if (uiState.drawerOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(interactionSource = null, indication = null) { onCloseDrawer() }
            )
            ModalDrawerSheet(
                modifier = Modifier
                    .width(320.dp)
                    .align(Alignment.CenterEnd)
            ) {
                LumiMenuContent(
                    onSelect = { action ->
                        onCloseDrawer()
                        applyAction(action)
                    }
                )
            }
        }
    }
}

@Composable
private fun MainContent(
    uiState: MainUiState,
    onTabSelected: (MainTab) -> Unit,
    onCloseOverlay: () -> Unit,
    onOpenStock: () -> Unit
) {
    val overlay = uiState.overlayPage
    if (overlay != null) {
        OverlayContent(
            page = overlay,
            onBack = onCloseOverlay,
            onOpenStock = onOpenStock
        )
    } else {
        when (uiState.selectedTab) {
            MainTab.POS -> PosScreen()
            MainTab.Sales -> SalesScreen()
            MainTab.Orders -> OrdersScreen()
            MainTab.Inventory -> InventoryHubScreen()
            MainTab.Reports -> ReportsScreen()
        }
    }
}

@Composable
private fun OverlayContent(
    page: MainOverlayPage,
    onBack: () -> Unit,
    onOpenStock: () -> Unit
) {
    when (page) {
        MainOverlayPage.Dashboard -> DashboardScreen(onBack = onBack, onOpenStock = onOpenStock)
        MainOverlayPage.AccountsReceivable -> CustomerBalancesScreen(onBack = onBack)
        MainOverlayPage.CashShift -> ShiftsScreen(onBack = onBack)
        MainOverlayPage.Expenses -> ExpensesScreen(onBack = onBack)
        MainOverlayPage.Products -> ProductsScreen(onBack = onBack)
        MainOverlayPage.Categories -> CategoriesScreen(onBack = onBack)
        MainOverlayPage.Stock -> StockControlScreen(onBack = onBack)
        MainOverlayPage.Purchases -> PurchasesScreen(onBack = onBack)
        MainOverlayPage.Customers -> CustomersScreen(onBack = onBack)
        MainOverlayPage.Suppliers -> SuppliersScreen(onBack = onBack)
        MainOverlayPage.Settings -> SettingsHubScreen(onBack = onBack)
        MainOverlayPage.Users -> UsersScreen(onBack = onBack)
        MainOverlayPage.StoreInfo -> StoreInfoScreen(onBack = onBack)
        MainOverlayPage.ReceiptSettings -> ReceiptCustomizationScreen(onBack = onBack)
        MainOverlayPage.AuditLogs -> AuditLogsScreen(onBack = onBack)
        MainOverlayPage.Backup -> BackupScreen(onBack = onBack)
        MainOverlayPage.About -> AboutScreen(onBack = onBack)
    }
}

@Composable
private fun LumiLeftRail(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            Text(
                text = "LumiPOS",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
    ) {
        MainTab.entries.forEach { tab ->
            NavigationRailItem(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                icon = { Icon(MainTabIcons.getValue(tab), contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimary,
                    indicatorColor = MaterialTheme.colorScheme.primary
                )
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun LumiNavigationBar(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    onOpenMenu: () -> Unit
) {
    NavigationBar {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                icon = { Icon(MainTabIcons.getValue(tab), contentDescription = tab.label) },
                label = { Text(tab.label) }
            )
        }
        NavigationBarItem(
            selected = false,
            onClick = onOpenMenu,
            icon = { Icon(Icons.Filled.Menu, contentDescription = "Menu") },
            label = { Text("Menu") }
        )
    }
}