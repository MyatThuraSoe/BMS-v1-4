package com.lumipos.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.LocalPrintshop
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.lumipos.app.ui.audit.AuditLogsScreen
import com.lumipos.app.ui.backup.BackupScreen
import com.lumipos.app.ui.expenses.ExpensesScreen
import com.lumipos.app.ui.receipts.ReceiptCustomizationScreen
import com.lumipos.app.ui.shifts.ShiftsScreen
import com.lumipos.app.ui.users.UsersScreen

private enum class SettingsSection { HOME, STORE, USERS, SHIFTS, EXPENSES, RECEIPT, AUDIT, BACKUP }

private data class SettingsEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val section: SettingsSection
)

private val settingsEntries = listOf(
    SettingsEntry("Store Info", "Shop name, address, currency, tax", Icons.Filled.Store, SettingsSection.STORE),
    SettingsEntry("Users", "Manage staff accounts and permissions", Icons.Filled.People, SettingsSection.USERS),
    SettingsEntry("Cash Shifts", "Open and close daily cash shifts", Icons.Filled.AccountBalanceWallet, SettingsSection.SHIFTS),
    SettingsEntry("Expenses", "Record and manage outcomes", Icons.Filled.Receipt, SettingsSection.EXPENSES),
    SettingsEntry("Receipt Settings", "Receipt layout, messages, toggles", Icons.Filled.LocalPrintshop, SettingsSection.RECEIPT),
    SettingsEntry("Audit Logs", "Security and activity trail", Icons.Filled.FactCheck, SettingsSection.AUDIT),
    SettingsEntry("Backup & Restore", "Export or import local backups", Icons.Filled.Backup, SettingsSection.BACKUP)
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(onBack: (() -> Unit)? = null) {
    var sectionName by rememberSaveable { mutableStateOf(SettingsSection.HOME.name) }
    val section = SettingsSection.entries.firstOrNull { it.name == sectionName } ?: SettingsSection.HOME

    when (section) {
        SettingsSection.HOME -> SettingsHome(onBack = onBack, onOpen = { sectionName = it.name })
        SettingsSection.STORE -> StoreInfoScreen(onBack = { sectionName = SettingsSection.HOME.name })
        SettingsSection.USERS -> UsersScreen(onBack = { sectionName = SettingsSection.HOME.name })
        SettingsSection.SHIFTS -> ShiftsScreen(onBack = { sectionName = SettingsSection.HOME.name })
        SettingsSection.EXPENSES -> ExpensesScreen(onBack = { sectionName = SettingsSection.HOME.name })
        SettingsSection.RECEIPT -> ReceiptCustomizationScreen(onBack = { sectionName = SettingsSection.HOME.name })
        SettingsSection.AUDIT -> AuditLogsScreen(onBack = { sectionName = SettingsSection.HOME.name })
        SettingsSection.BACKUP -> BackupScreen(onBack = { sectionName = SettingsSection.HOME.name })
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(onBack: (() -> Unit)? = null, onOpen: (SettingsSection) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                    }
                }
            )
        }
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
                    items(settingsEntries) { entry ->
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