package com.lumipos.app.ui.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.data.export.ExportImportManager
import com.lumipos.domain.usecase.export.ExportBackupUseCase
import com.lumipos.domain.usecase.export.ImportBackupUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BackupUiState(
    val isLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val exportBackupUseCase: ExportBackupUseCase,
    private val importBackupUseCase: ImportBackupUseCase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState

    fun export() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = null, error = null) }
            val result = exportBackupUseCase()
            result.fold(
                onSuccess = { path ->
                    _uiState.update { it.copy(isLoading = false, message = "Backup saved to:\n$path") }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Export failed") }
                }
            )
        }
    }

    fun import(uri: Uri, mode: ExportImportManager.ImportMode) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = null, error = null) }
            try {
                val cacheFile = File(context.cacheDir, "lumipos_import_${System.currentTimeMillis()}.json")
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Could not open selected file")
                input.use { ins ->
                    cacheFile.outputStream().use { out -> ins.copyTo(out) }
                }
                val result = importBackupUseCase(cacheFile.absolutePath, mode)
                cacheFile.delete()
                result.fold(
                    onSuccess = { msg ->
                        _uiState.update { it.copy(isLoading = false, message = msg) }
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(isLoading = false, error = e.message ?: "Import failed") }
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Import failed") }
            }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }
}