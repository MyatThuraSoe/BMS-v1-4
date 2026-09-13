package com.lumipos.app.ui.print

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumipos.data.print.ReceiptData
import com.lumipos.app.ui.components.EmptyState
import com.lumipos.app.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothPrinterSheet(
    payload: ReceiptData,
    onDismiss: () -> Unit,
    viewModel: PrinterViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.load() else viewModel.permissionError()
    }

    LaunchedEffect(Unit) {
        val needsPermission = Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            viewModel.load()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Choose Printer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            HorizontalDivider()

            if (uiState.isLoading) {
                LoadingState(Modifier.height(100.dp))
            } else if (uiState.devices.isEmpty()) {
                EmptyState("No paired printers found.\nPair your printer in Bluetooth settings first.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(uiState.devices) { device ->
                        val isPrinting = uiState.printingDevice == device.address
                        ListItem(
                            headlineContent = { Text(device.name) },
                            supportingContent = { Text(device.address, style = MaterialTheme.typography.bodySmall) },
                            trailingContent = {
                                if (isPrinting) {
                                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                                }
                            },
                            modifier = Modifier.clickable(enabled = !isPrinting) {
                                viewModel.print(device.address, payload)
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    uiState.message?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissMessage(); onDismiss() },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissMessage(); onDismiss() }) { Text("OK") }
            },
            title = { Text("Print") },
            text = { Text(message) }
        )
    }

    uiState.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } },
            title = { Text("Print Error") },
            text = { Text(error) }
        )
    }
}