package com.lumipos.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

sealed interface MenuAction {
    data class Tab(val tab: MainTab) : MenuAction
    data class Page(val page: MainOverlayPage) : MenuAction
}

data class MenuItem(
    val label: String,
    val icon: ImageVector,
    val action: MenuAction
)

data class MenuGroup(
    val label: String,
    val items: List<MenuItem>
)

private val menuGroups = listOf(
    MenuGroup(
        label = "OVERVIEW",
        items = listOf(
            MenuItem("Dashboard", Icons.Filled.SpaceDashboard, MenuAction.Page(MainOverlayPage.Dashboard))
        )
    ),
    MenuGroup(
        label = "SALES",
        items = listOf(
            MenuItem("POS", Icons.Filled.PointOfSale, MenuAction.Tab(MainTab.POS)),
            MenuItem("Orders", Icons.AutoMirrored.Filled.ListAlt, MenuAction.Tab(MainTab.Orders)),
            MenuItem("Sales", Icons.Filled.ReceiptLong, MenuAction.Tab(MainTab.Sales)),
            MenuItem("Cash Shift", Icons.Filled.AccountBalanceWallet, MenuAction.Page(MainOverlayPage.CashShift)),
            MenuItem("Accounts Receivable", Icons.Filled.Payments, MenuAction.Page(MainOverlayPage.AccountsReceivable)),
            MenuItem("Expenses", Icons.Filled.ShoppingCart, MenuAction.Page(MainOverlayPage.Expenses))
        )
    ),
    MenuGroup(
        label = "CATALOG",
        items = listOf(
            MenuItem("Inventory", Icons.Filled.Warehouse, MenuAction.Tab(MainTab.Inventory)),
            MenuItem("Products", Icons.Filled.Inventory2, MenuAction.Page(MainOverlayPage.Products)),
            MenuItem("Categories", Icons.Filled.Category, MenuAction.Page(MainOverlayPage.Categories)),
            MenuItem("Stock Control", Icons.Filled.Inventory2, MenuAction.Page(MainOverlayPage.Stock))
        )
    ),
    MenuGroup(
        label = "PROCUREMENT",
        items = listOf(
            MenuItem("Purchases", Icons.Filled.ShoppingCart, MenuAction.Page(MainOverlayPage.Purchases))
        )
    ),
    MenuGroup(
        label = "PEOPLE",
        items = listOf(
            MenuItem("Customers", Icons.Filled.People, MenuAction.Page(MainOverlayPage.Customers)),
            MenuItem("Suppliers", Icons.Filled.LocalShipping, MenuAction.Page(MainOverlayPage.Suppliers))
        )
    ),
    MenuGroup(
        label = "INSIGHTS",
        items = listOf(
            MenuItem("Reports", Icons.Filled.Assessment, MenuAction.Tab(MainTab.Reports))
        )
    ),
    MenuGroup(
        label = "ADMINISTRATION",
        items = listOf(
            MenuItem("Settings", Icons.Filled.Settings, MenuAction.Page(MainOverlayPage.Settings)),
            MenuItem("Users", Icons.Filled.ManageAccounts, MenuAction.Page(MainOverlayPage.Users)),
            MenuItem("Store Info", Icons.Filled.Storefront, MenuAction.Page(MainOverlayPage.StoreInfo)),
            MenuItem("Receipt Settings", Icons.Filled.Print, MenuAction.Page(MainOverlayPage.ReceiptSettings)),
            MenuItem("Backup & Restore", Icons.Filled.CloudUpload, MenuAction.Page(MainOverlayPage.Backup)),
            MenuItem("Audit Logs", Icons.Filled.FactCheck, MenuAction.Page(MainOverlayPage.AuditLogs))
        )
    ),
    MenuGroup(
        label = "APP",
        items = listOf(
            MenuItem("About", Icons.Filled.Info, MenuAction.Page(MainOverlayPage.About))
        )
    )
)

val menuDrawerItems: List<MenuGroup> get() = menuGroups

/** Full grouped menu used inside the end drawer (phones) and as content for the tablet right bar. */
@Composable
internal fun LumiMenuContent(
    onSelect: (MenuAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .height(10.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.small
                    )
            )
            Text(
                text = "LumiPOS",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp)
        ) {
            menuGroups.forEach { group ->
                item(key = "header_${group.label}") {
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
                items(group.items, key = { it.label }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(item.action) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(28.dp)
                        )
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

/** Compact shortcut list used by the always-visible right "menu bar" on wide screens. */
internal val menuRailShortcuts: List<MenuItem> = listOf(
    MenuItem("Dashboard", Icons.Filled.SpaceDashboard, MenuAction.Page(MainOverlayPage.Dashboard)),
    MenuItem("Settings", Icons.Filled.Settings, MenuAction.Page(MainOverlayPage.Settings)),
    MenuItem("Users", Icons.Filled.ManageAccounts, MenuAction.Page(MainOverlayPage.Users)),
    MenuItem("Cash Shift", Icons.Filled.AccountBalanceWallet, MenuAction.Page(MainOverlayPage.CashShift)),
    MenuItem("Backup", Icons.Filled.Backup, MenuAction.Page(MainOverlayPage.Backup)),
    MenuItem("Audit Logs", Icons.Filled.FactCheck, MenuAction.Page(MainOverlayPage.AuditLogs)),
    MenuItem("About", Icons.Filled.Info, MenuAction.Page(MainOverlayPage.About))
)

/** The right-side "menu bar": a NavigationRail with a Menu trigger plus the shortcut entries. */
@Composable
internal fun LumiMenuRail(
    selectedPage: MainOverlayPage?,
    onOpenMenu: () -> Unit,
    onSelect: (MenuAction) -> Unit
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            Text(
                text = "Menu",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        }
    ) {
        NavigationRailItem(
            selected = false,
            onClick = onOpenMenu,
            icon = { Icon(Icons.Filled.Menu, contentDescription = "Open Menu") },
            label = { Text("All") },
            colors = NavigationRailItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                selectedTextColor = MaterialTheme.colorScheme.onPrimary,
                indicatorColor = MaterialTheme.colorScheme.primary
            )
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        menuRailShortcuts.forEach { shortcut ->
            val page = (shortcut.action as? MenuAction.Page)?.page
            NavigationRailItem(
                selected = page == selectedPage && page != null,
                onClick = { onSelect(shortcut.action) },
                icon = { Icon(shortcut.icon, contentDescription = shortcut.label) },
                label = { Text(shortcut.label) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimary,
                    indicatorColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}