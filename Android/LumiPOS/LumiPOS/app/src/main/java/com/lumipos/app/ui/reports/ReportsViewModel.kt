package com.lumipos.app.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.domain.usecase.export.ExportCsvUseCase
import com.lumipos.domain.usecase.report.ProfitReport
import com.lumipos.domain.usecase.report.ProfitReportUseCase
import com.lumipos.domain.usecase.report.SalesReportUseCase
import com.lumipos.domain.usecase.session.ObserveSessionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ReportRange(val label: String) {
    TODAY("Today"),
    WEEK("This Week"),
    MONTH("This Month"),
    ALL("All Time")
}

data class ReportsUiState(
    val selectedRange: ReportRange = ReportRange.TODAY,
    val profit: ProfitReport? = null,
    val totalSales: Double? = null,
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val salesReportUseCase: SalesReportUseCase,
    private val profitReportUseCase: ProfitReportUseCase,
    private val exportCsvUseCase: ExportCsvUseCase,
    private val observeSessionUseCase: ObserveSessionUseCase
) : ViewModel() {

    private var branchId: Long = 0L
    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState

    init {
        viewModelScope.launch {
            observeSessionUseCase().first().branchId?.let { branchId = it }
            loadRange(ReportRange.TODAY)
        }
    }

    fun selectRange(range: ReportRange) = loadRange(range)

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }

    private fun loadRange(range: ReportRange) {
        val (start, end) = rangeBounds(range)
        viewModelScope.launch {
            _uiState.update { it.copy(selectedRange = range, isLoading = true, error = null) }
            try {
                val profit = profitReportUseCase.generateReport(start, end, branchId)
                val totalSales = salesReportUseCase.getTotalSales(start, end, branchId)
                _uiState.update {
                    it.copy(
                        profit = profit,
                        totalSales = totalSales,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun exportCsv() {
        val range = _uiState.value.selectedRange
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, message = null, error = null) }
            try {
                val (start, end) = rangeBounds(range)
                val profit = profitReportUseCase.generateReport(start, end, branchId)
                val totalSales = salesReportUseCase.getTotalSales(start, end, branchId)
                val fileName = "LumiPOS_Report_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
                exportCsvUseCase(fileName, buildCsv(range, profit, totalSales)).fold(
                    onSuccess = { path ->
                        _uiState.update { it.copy(isExporting = false, message = "Saved to:\n$path") }
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(isExporting = false, error = e.message ?: "Export failed") }
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(isExporting = false, error = e.message ?: "Export failed") }
            }
        }
    }

    private fun buildCsv(range: ReportRange, profit: ProfitReport, totalSales: Double?): String {
        val two: (Double) -> String = { "%.2f".format(it) }
        return buildString {
            appendLine("LumiPOS Report")
            appendLine("Range,${range.label}")
            appendLine("Revenue,${two(profit.totalRevenue)}")
            appendLine("Cost of Goods,${two(profit.totalCost)}")
            appendLine("Gross Profit,${two(profit.totalProfit)}")
            appendLine("Expenses,${two(profit.totalExpenses)}")
            appendLine("Net Profit,${two(profit.netProfit)}")
            totalSales?.let { appendLine("Total Sales,${two(it)}") }
        }
    }

    private fun rangeBounds(range: ReportRange): Pair<Long, Long> {
        val now = Calendar.getInstance()
        val start = Calendar.getInstance().apply {
            clear(Calendar.HOUR_OF_DAY); clear(Calendar.MINUTE); clear(Calendar.SECOND); clear(Calendar.MILLISECOND)
        }
        when (range) {
            ReportRange.TODAY -> { /* start at midnight today */ }
            ReportRange.WEEK -> start.set(Calendar.DAY_OF_WEEK, start.firstDayOfWeek)
            ReportRange.MONTH -> start.set(Calendar.DAY_OF_MONTH, 1)
            ReportRange.ALL -> return 0L to Long.MAX_VALUE
        }
        return start.timeInMillis to now.timeInMillis
    }
}