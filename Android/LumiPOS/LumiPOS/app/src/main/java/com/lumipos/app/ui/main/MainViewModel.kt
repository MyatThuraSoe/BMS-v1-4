package com.lumipos.app.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class MainTab(
    val label: String
) {
    POS("POS"),
    Sales("Sales"),
    Orders("Orders"),
    Inventory("Inventory"),
    Reports("Reports")
}

enum class MainOverlayPage(
    val label: String
) {
    Dashboard("Dashboard"),
    AccountsReceivable("Accounts Receivable"),
    CashShift("Cash Shift"),
    Expenses("Expenses"),
    Products("Products"),
    Categories("Categories"),
    Stock("Stock Control"),
    Purchases("Purchases"),
    Customers("Customers"),
    Suppliers("Suppliers"),
    Settings("Settings"),
    Users("Users"),
    StoreInfo("Store Info"),
    ReceiptSettings("Receipt Settings"),
    Backup("Backup & Restore"),
    AuditLogs("Audit Logs"),
    About("About")
}

data class MainUiState(
    val selectedTab: MainTab = MainTab.POS,
    val overlayPage: MainOverlayPage? = null,
    val drawerOpen: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(selectedTab = tab, overlayPage = null) }
    }

    fun selectOverlay(page: MainOverlayPage) {
        _uiState.update { it.copy(overlayPage = page) }
    }

    fun closeOverlay() {
        _uiState.update { it.copy(overlayPage = null) }
    }

    fun toggleDrawer() {
        _uiState.update { it.copy(drawerOpen = !it.drawerOpen) }
    }

    fun closeDrawer() {
        _uiState.update { it.copy(drawerOpen = false) }
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    LumiPOSAppScaffold(
        uiState = uiState,
        onTabSelected = viewModel::selectTab,
        onSelectOverlay = viewModel::selectOverlay,
        onCloseOverlay = viewModel::closeOverlay,
        onToggleDrawer = viewModel::toggleDrawer,
        onCloseDrawer = viewModel::closeDrawer
    )
}