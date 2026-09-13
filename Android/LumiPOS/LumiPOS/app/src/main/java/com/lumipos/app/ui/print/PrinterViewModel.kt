package com.lumipos.app.ui.print

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.data.print.PrinterDevice
import com.lumipos.data.print.ReceiptData
import com.lumipos.domain.usecase.print.GetPairedPrintersUseCase
import com.lumipos.domain.usecase.print.PrintReceiptUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PrinterUiState(
    val devices: List<PrinterDevice> = emptyList(),
    val isLoading: Boolean = false,
    val printingDevice: String? = null,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class PrinterViewModel @Inject constructor(
    private val getPairedPrintersUseCase: GetPairedPrintersUseCase,
    private val printReceiptUseCase: PrintReceiptUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrinterUiState())
    val uiState: StateFlow<PrinterUiState> = _uiState

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        getPairedPrintersUseCase().fold(
            onSuccess = { devices ->
                _uiState.update { it.copy(devices = devices, isLoading = false) }
            },
            onFailure = { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Could not list printers") }
            }
        )
    }

    fun print(address: String, payload: ReceiptData) {
        viewModelScope.launch {
            _uiState.update { it.copy(printingDevice = address, message = null, error = null) }
            printReceiptUseCase(address, payload).fold(
                onSuccess = { msg ->
                    _uiState.update { it.copy(printingDevice = null, message = msg) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(printingDevice = null, error = e.message ?: "Print failed") }
                }
            )
        }
    }

    fun permissionError() {
        _uiState.update { it.copy(error = "Bluetooth permission required. Grant the permission in Settings.") }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }
}