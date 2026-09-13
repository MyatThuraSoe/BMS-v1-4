package com.lumipos.app.ui.receipts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumipos.app.ui.components.LoadingState
import com.lumipos.data.print.ReceiptBuilder
import com.lumipos.data.print.ReceiptData
import com.lumipos.data.print.ReceiptLine

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReceiptCustomizationScreen(
    onBack: () -> Unit,
    viewModel: ReceiptCustomizationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BoxWithConstraints {
        val isExpanded = maxWidth >= 900.dp
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Receipt Customization") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                    }
                )
            }
        ) { padding ->
            if (uiState.isLoading) {
                LoadingState(Modifier.padding(padding))
            } else if (isExpanded) {
                Row(Modifier.fillMaxSize().padding(padding)) {
                    Column(
                        Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())
                    ) {
                        ControlsColumn(uiState, viewModel)
                    }
                    Column(
                        Modifier.weight(1f).fillMaxSize()
                    ) {
                        ReceiptPreviewPane(
                            data = remember(uiState) { viewModel.buildPreviewReceipt() },
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        )
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                ) {
                    ReceiptPreviewPane(
                        data = remember(uiState) { viewModel.buildPreviewReceipt() },
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    )
                    ControlsColumn(uiState, viewModel)
                }
            }
        }
    }

    uiState.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
            title = { Text("Done") },
            text = { Text(message) }
        )
    }

    uiState.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } },
            title = { Text("Error") },
            text = { Text(error) }
        )
    }
}

@Composable
private fun ControlsColumn(uiState: ReceiptCustomizationUiState, viewModel: ReceiptCustomizationViewModel) {
    SectionCard(title = "Messages") {
        OutlinedTextField(
            value = uiState.headerText,
            onValueChange = viewModel::setHeaderText,
            label = { Text("Header text") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = uiState.mainMessage,
            onValueChange = viewModel::setMainMessage,
            label = { Text("Main message") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = uiState.footerText,
            onValueChange = viewModel::setFooterText,
            label = { Text("Footer text") },
            modifier = Modifier.fillMaxWidth()
        )
    }

    SectionCard(title = "Layout") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Header alignment", modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("LEFT", "CENTER", "RIGHT").forEach { a ->
                    FilterChip(
                        selected = uiState.headerAlign == a,
                        onClick = { viewModel.setHeaderAlign(a) },
                        label = { Text(a) }
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Divider style", modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("SOLID", "DASHED").forEach { d ->
                    FilterChip(
                        selected = uiState.dividerStyle == d,
                        onClick = { viewModel.setDividerStyle(d) },
                        label = { Text(d) }
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Time format", modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("12h", "24h").forEach { t ->
                    FilterChip(
                        selected = uiState.timeFormat == t,
                        onClick = { viewModel.setTimeFormat(t) },
                        label = { Text(t) }
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Paper size", modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(58, 80).forEach { p ->
                    FilterChip(
                        selected = uiState.paperSize == p,
                        onClick = { viewModel.setPaperSize(p) },
                        label = { Text("${p}mm") }
                    )
                }
            }
        }
    }

    SectionCard(title = "Content toggles") {
        ToggleRow("Show shop name", uiState.showShopName, viewModel::setShowShopName)
        ToggleRow("Bold shop name", uiState.boldShopName, viewModel::setBoldShopName)
        ToggleRow("Show address", uiState.showAddress, viewModel::setShowAddress)
        ToggleRow("Show phone", uiState.showPhone, viewModel::setShowPhone)
        ToggleRow("Show QR code", uiState.showQRCode, viewModel::setShowQRCode)
        ToggleRow("Show item SKU", uiState.showItemSku, viewModel::setShowItemSku)
        ToggleRow("Show tax info", uiState.showTaxInfo, viewModel::setShowTaxInfo)
        ToggleRow("Show discount info", uiState.showDiscountInfo, viewModel::setShowDiscountInfo)
        ToggleRow("Show credit info", uiState.showCreditInfo, viewModel::setShowCreditInfo)
    }

    Button(
        onClick = viewModel::save,
        enabled = !uiState.isSaving,
        modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
        Text(if (uiState.isSaving) "Saving..." else "Save Receipt Settings")
    }
}

@Composable
private fun ReceiptPreviewPane(data: ReceiptData, modifier: Modifier = Modifier) {
    val cols = if (data.paperSize <= 58) 24 else 32
    val annotated = remember(data) { buildReceiptAnnotated(data, cols) }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live Preview", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${data.paperSize} mm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .width((cols * 7.2f).dp)
                        .background(Color.White)
                        .border(1.dp, Color(0xFFCCCCCC))
                        .padding(horizontal = 10.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFE0E0E0)))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        annotated,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF111111)
                    )
                    if (data.qrContent != null) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .width(72.dp)
                                .height(72.dp)
                                .border(1.dp, Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("QR", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFE0E0E0)))
                }
            }
        }
    }
}

private fun buildReceiptAnnotated(data: ReceiptData, cols: Int): AnnotatedString = buildAnnotatedString {
    fun add(text: String, bold: Boolean) {
        withStyle(SpanStyle(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)) {
            append(text)
            append('\n')
        }
    }

    fun align(text: String): String {
        if (text.length >= cols) return text.take(cols)
        return when (data.headerAlign) {
            "LEFT" -> text
            "RIGHT" -> " ".repeat(cols - text.length) + text
            else -> {
                val spaces = (cols - text.length) / 2
                " ".repeat(spaces) + text
            }
        }
    }

    fun addWrapped(text: String, bold: Boolean) {
        text.chunked(cols).forEach { add(align(it), bold) }
    }

    fun addLine(line: ReceiptLine) {
        if (line.divider) {
            add(ReceiptBuilder.dividerChars(cols, line.dashed), false)
        } else if (line.center) {
            addWrapped(line.left, line.bold)
        } else {
            val right = line.right
            if (right != null) {
                add(ReceiptBuilder.twoCol(cols, line.left, right), line.bold)
            } else {
                line.left.chunked(cols).forEach { add(it, line.bold) }
            }
        }
    }

    if (data.storeName.isNotBlank()) {
        addWrapped(data.storeName, data.showBoldShopName)
        data.storeAddress.split('\n').filter { it.isNotBlank() }.forEach { addWrapped(it, false) }
    }
    if (data.headerText.isNotBlank()) addWrapped(data.headerText, true)
    data.header.forEach { addLine(it) }
    data.items.forEach { addLine(it) }
    data.totals.forEach { addLine(it) }
    if (data.footer.isNotBlank()) addWrapped(data.footer, true)
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
    HorizontalDivider(Modifier.padding(vertical = 2.dp))
}