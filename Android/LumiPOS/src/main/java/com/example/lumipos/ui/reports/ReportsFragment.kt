package com.example.lumipos.ui.reports

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.ReportTopProductAdapter
import com.example.lumipos.adapter.SaleAdapter
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter
import com.example.lumipos.util.Extensions.toast
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ReportsFragment : Fragment() {

    private val viewModel: ReportsViewModel by viewModels()
    private var startDate: Long = DateFormatter.getTodayStart()
    private var endDate: Long = DateFormatter.getTodayEnd()
    private var currentChart = "daily"
    private var dailyCardView: View? = null
    private var topAdapter: ReportTopProductAdapter? = null
    private var topEmptyView: TextView? = null
    private var salesAdapter: SaleAdapter? = null
    private var taxViews: MutableMap<String, TextView> = mutableMapOf()
    private var latestTaxReport: TaxLiabilityReport? = null
    private var reorderContainer: LinearLayout? = null
    private var reorderEmpty: TextView? = null
    private var deadStockContainer: LinearLayout? = null
    private var deadStockEmpty: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_reports, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<android.widget.EditText>(R.id.et_start_date).setText(DateFormatter.formatDate(startDate))
        view.findViewById<android.widget.EditText>(R.id.et_end_date).setText(DateFormatter.formatDate(endDate))

        view.findViewById<android.widget.EditText>(R.id.et_start_date).setOnClickListener {
            showDatePicker { year, month, day ->
                startDate = DateFormatter.toStartOfDay(year, month, day)
                view.findViewById<android.widget.EditText>(R.id.et_start_date).setText(DateFormatter.formatDate(startDate))
                reload()
            }
        }
        view.findViewById<android.widget.EditText>(R.id.et_end_date).setOnClickListener {
            showDatePicker { year, month, day ->
                endDate = DateFormatter.toEndOfDay(year, month, day)
                view.findViewById<android.widget.EditText>(R.id.et_end_date).setText(DateFormatter.formatDate(endDate))
                reload()
            }
        }

        salesAdapter = SaleAdapter { sale -> showSaleDetails(sale) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.dateRangeSales.collectLatest { paged ->
                salesAdapter?.submitData(paged)
            }
        }

        salesAdapter?.addLoadStateListener { state ->
            if (currentChart != "daily") return@addLoadStateListener
            val card = dailyCardView ?: return@addLoadStateListener
            if (state.refresh is LoadState.NotLoading) renderDailyEmptyState(card)
        }

        val chipContainer = view.findViewById<LinearLayout>(R.id.report_type_chips)
        val reportTypes = listOf(
            "daily" to getString(R.string.report_tab_daily),
            "trend" to getString(R.string.report_tab_trend),
            "top" to getString(R.string.report_tab_top),
            "tax" to getString(R.string.report_tab_tax),
            "reorder" to getString(R.string.report_tab_reorder),
            "dead" to getString(R.string.report_tab_dead)
        )

        val chips = mutableListOf<MaterialButton>()
        // Without this the three buttons looked identical, so there was no way to tell which
        // report was currently on screen.
        fun highlightSelected(target: String) {
            chips.forEach { chip ->
                val active = chip.tag == target
                chip.backgroundTintList = ColorStateList.valueOf(
                    requireContext().getColor(if (active) R.color.primary else R.color.surface)
                )
                chip.setTextColor(
                    requireContext().getColor(if (active) R.color.on_primary else R.color.text_primary)
                )
            }
        }

        for ((key, label) in reportTypes) {
            val chip = MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                tag = key
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    when (key) {
                        "daily" -> showDaily(view)
                        "trend" -> showTrend(view)
                        "top" -> showTopProducts(view)
                        "tax" -> showTax(view)
                        "reorder" -> showReorder(view)
                        "dead" -> showDeadStock(view)
                    }
                    highlightSelected(key)
                }
            }
            chips.add(chip)
            chipContainer.addView(chip)
        }
        highlightSelected("daily")

        viewModel.dailyReport.observe(viewLifecycleOwner) { report ->
            if (currentChart != "daily" || report == null) return@observe
            val card = dailyCardView ?: return@observe
            card.findViewById<TextView>(R.id.tv_stat_transactions_value).text =
                report.totalTransactions.toString()
            card.findViewById<TextView>(R.id.tv_stat_revenue_value).text =
                CurrencyFormatter.format(report.totalRevenue)
            // These two were computed but had no view to land in, so the numbers were dropped.
            card.findViewById<TextView>(R.id.tv_stat_items_value).text =
                report.totalItemsSold.toString()
            card.findViewById<TextView>(R.id.tv_stat_avg_value).text =
                CurrencyFormatter.format(report.averageTransactionValue)
        }

        viewModel.salesTrend.observe(viewLifecycleOwner) { trend ->
            if (currentChart == "trend" && trend != null) {
                renderTrendChart(view, trend)
            }
        }

        viewModel.topProducts.observe(viewLifecycleOwner) { products ->
            if (currentChart == "top") {
                topAdapter?.submitList(products)
                topEmptyView?.visibility = if (products.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewModel.taxLiability.observe(viewLifecycleOwner) { report ->
            if (currentChart != "tax" || report == null) return@observe
            latestTaxReport = report
            taxViews["output"]?.text = CurrencyFormatter.format(report.outputTax)
            taxViews["input_purchases"]?.text = CurrencyFormatter.format(report.inputTaxPurchases)
            taxViews["input_expenses"]?.text = CurrencyFormatter.format(report.inputTaxExpenses)
            taxViews["input_total"]?.text = CurrencyFormatter.format(report.inputTax)
            taxViews["net"]?.text = CurrencyFormatter.format(report.netPayable)
        }

        viewModel.deadStock.observe(viewLifecycleOwner) { entries ->
            if (currentChart == "dead") renderDeadStock(entries ?: emptyList())
        }

        viewModel.reorderSuggestions.observe(viewLifecycleOwner) { suggestions ->
            if (currentChart == "reorder") renderReorder(suggestions ?: emptyList())
        }

        showDaily(view)
    }

    private fun reload() {
        when (currentChart) {
            "daily" -> viewModel.loadDailyReport(startDate, endDate)
            "trend" -> viewModel.loadSalesTrend(7)
            "top" -> viewModel.loadTopProducts(startDate, endDate)
            "tax" -> viewModel.loadTaxLiability(startDate, endDate)
            "reorder" -> viewModel.loadReorderSuggestions()
            "dead" -> viewModel.loadDeadStock()
        }
    }

    private fun showDaily(view: View) {
        currentChart = "daily"
        val frame = view.findViewById<NestedScrollView>(R.id.content_frame)
        frame.removeAllViews()
        val card = LayoutInflater.from(requireContext())
            .inflate(R.layout.report_daily_card, frame, false)
        dailyCardView = card
        card.findViewById<TextView>(R.id.tv_report_title).text = getString(R.string.report_daily_title)
        card.findViewById<TextView>(R.id.tv_report_date).text =
            "${DateFormatter.formatDate(startDate)} – ${DateFormatter.formatDate(endDate)}"

        card.findViewById<RecyclerView>(R.id.rv_report_items).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = salesAdapter
        }

        frame.addView(card)
        renderDailyEmptyState(card)
        viewModel.loadDailyReport(startDate, endDate)
        viewModel.loadDateRangeSales(startDate, endDate)
    }

    private fun renderDailyEmptyState(card: View) {
        val empty = (salesAdapter?.itemCount ?: 0) == 0
        card.findViewById<View>(R.id.tv_no_sales).visibility = if (empty) View.VISIBLE else View.GONE
        card.findViewById<View>(R.id.rv_report_items).visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun showTrend(view: View) {
        currentChart = "trend"
        val frame = view.findViewById<NestedScrollView>(R.id.content_frame)
        frame.removeAllViews()
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(TextView(requireContext()).apply {
            text = getString(R.string.report_sales_trend)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(8))
        })
        val chartContainer = FrameLayout(requireContext())
        chartContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(260)
        )
        layout.addView(chartContainer)
        frame.addView(layout)
        viewModel.loadSalesTrend(7)
    }

    private fun showTopProducts(view: View) {
        currentChart = "top"
        val frame = view.findViewById<NestedScrollView>(R.id.content_frame)
        frame.removeAllViews()
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(TextView(requireContext()).apply {
            text = getString(R.string.report_top_products)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(8))
        })
        val rv = RecyclerView(requireContext())
        rv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        rv.layoutManager = LinearLayoutManager(requireContext())
        topAdapter = ReportTopProductAdapter(emptyList())
        rv.adapter = topAdapter

        val emptyView = TextView(requireContext()).apply {
            text = getString(R.string.report_no_top_products)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        topEmptyView = emptyView

        layout.addView(rv)
        layout.addView(emptyView)
        frame.addView(layout)
        viewModel.loadTopProducts(startDate, endDate)
    }

    /**
     * VAT/GST position for the selected period: tax collected on sales (output) minus tax paid on
     * purchases and expenses (input). Exportable as CSV for filing with the tax authority.
     */
    private fun showTax(view: View) {
        currentChart = "tax"
        taxViews = mutableMapOf()
        latestTaxReport = null
        val frame = view.findViewById<NestedScrollView>(R.id.content_frame)
        frame.removeAllViews()
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(TextView(requireContext()).apply {
            text = getString(R.string.report_tax_title)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(4))
        })
        layout.addView(TextView(requireContext()).apply {
            text = "${DateFormatter.formatDate(startDate)} – ${DateFormatter.formatDate(endDate)}"
            setPadding(dp(16), 0, dp(16), dp(8))
        })

        fun addRow(key: String, labelRes: Int, bold: Boolean = false) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(6), dp(16), dp(6))
            }
            row.addView(TextView(requireContext()).apply {
                text = getString(labelRes)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            })
            val value = TextView(requireContext()).apply {
                text = "—"
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            }
            row.addView(value)
            taxViews[key] = value
            layout.addView(row)
        }

        addRow("output", R.string.label_output_tax)
        addRow("input_purchases", R.string.label_input_tax_purchases)
        addRow("input_expenses", R.string.label_input_tax_expenses)
        addRow("input_total", R.string.label_input_tax_total, bold = true)
        addRow("net", R.string.label_tax_payable, bold = true)

        val exportButton = MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.btn_export_csv)
            isAllCaps = false
            setOnClickListener { exportTaxCsv() }
        }
        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(16)
            topMargin = dp(8)
        }
        exportButton.layoutParams = buttonParams
        layout.addView(exportButton)

        frame.addView(layout)
        viewModel.loadTaxLiability(startDate, endDate)
    }

    private fun exportTaxCsv() {
        val report = latestTaxReport
        if (report == null) {
            toast(getString(R.string.msg_tax_report_not_ready))
            return
        }
        try {
            val dir = java.io.File(requireContext().cacheDir, "reports").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val file = java.io.File(dir, "tax-liability-$stamp.csv")
            val period = "${DateFormatter.formatDate(startDate)} to ${DateFormatter.formatDate(endDate)}"
            file.writeText(
                buildString {
                    appendLine("LumiPOS Tax Liability Report")
                    appendLine("Period,$period")
                    appendLine("Line,Amount")
                    appendLine("Output tax (collected),${report.outputTax}")
                    appendLine("Input tax (purchases),${report.inputTaxPurchases}")
                    appendLine("Input tax (expenses),${report.inputTaxExpenses}")
                    appendLine("Input tax (total),${report.inputTax}")
                    appendLine("Net tax payable,${report.netPayable}")
                }
            )
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.btn_export_csv)))
        } catch (e: Exception) {
            toast(getString(R.string.msg_backup_failed, e.message ?: "unknown error"))
        }
    }

    // ---------------------------------------------------- predictive analytics

    private fun showReorder(view: View) {
        currentChart = "reorder"
        val frame = view.findViewById<NestedScrollView>(R.id.content_frame)
        frame.removeAllViews()
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(TextView(requireContext()).apply {
            text = getString(R.string.report_reorder_title)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(8))
        })
        val empty = TextView(requireContext()).apply {
            text = getString(R.string.msg_no_reorder_suggestions)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        reorderEmpty = empty
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        reorderContainer = container
        layout.addView(container)
        layout.addView(empty)
        frame.addView(layout)
        viewModel.loadReorderSuggestions()
    }

    private fun renderReorder(suggestions: List<ReorderSuggestion>) {
        val container = reorderContainer ?: return
        container.removeAllViews()
        reorderEmpty?.visibility = if (suggestions.isEmpty()) View.VISIBLE else View.GONE
        for (entry in suggestions) {
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_insight_row, container, false)
            row.findViewById<TextView>(R.id.tv_insight_title).text = entry.productName
            val coverText = if (entry.dailyVelocity > 0.0) {
                getString(
                    R.string.format_reorder_subtitle,
                    entry.stockQuantity,
                    entry.dailyVelocity,
                    entry.suggestedReorderQty
                )
            } else {
                getString(R.string.format_reorder_low_stock, entry.stockQuantity, entry.suggestedReorderQty)
            }
            row.findViewById<TextView>(R.id.tv_insight_subtitle).text = coverText
            container.addView(row)
        }
    }

    private fun showDeadStock(view: View) {
        currentChart = "dead"
        val frame = view.findViewById<NestedScrollView>(R.id.content_frame)
        frame.removeAllViews()
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(TextView(requireContext()).apply {
            text = getString(R.string.report_dead_stock_title)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(8))
        })
        val empty = TextView(requireContext()).apply {
            text = getString(R.string.msg_no_dead_stock)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        deadStockEmpty = empty
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        deadStockContainer = container
        layout.addView(container)
        layout.addView(empty)
        frame.addView(layout)
        viewModel.loadDeadStock()
    }

    private fun renderDeadStock(entries: List<DeadStockEntry>) {
        val container = deadStockContainer ?: return
        container.removeAllViews()
        deadStockEmpty?.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        for (entry in entries) {
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_insight_row, container, false)
            row.findViewById<TextView>(R.id.tv_insight_title).text = entry.productName
            val lastSold = entry.lastSoldAt?.let { DateFormatter.formatDate(it) }
                ?: getString(R.string.msg_never_sold)
            row.findViewById<TextView>(R.id.tv_insight_subtitle).text = getString(
                R.string.format_dead_stock_subtitle,
                entry.stockQuantity,
                CurrencyFormatter.format(entry.stockValue),
                lastSold
            )
            container.addView(row)
        }
    }

    private fun renderTrendChart(view: View, trend: List<SalesTrendEntry>) {
        val frame = view.findViewById<NestedScrollView>(R.id.content_frame)
        if (frame.childCount == 0) return
        val layout = frame.getChildAt(0)
        val chartContainer = if (layout is LinearLayout && layout.childCount > 1) {
            layout.getChildAt(1) as? FrameLayout
        } else {
            null
        } ?: return

        chartContainer.removeAllViews()
        if (trend.isEmpty()) return

        val barChart = BarChart(requireContext())
        barChart.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        val entries = trend.mapIndexed { index, entry ->
            BarEntry(index.toFloat(), entry.totalSales.toFloat())
        }
        val dataSet = BarDataSet(entries, "Sales").apply {
            color = requireContext().getColor(R.color.primary)
            valueTextSize = 10f
        }
        barChart.data = BarData(dataSet)
        barChart.description.isEnabled = false
        barChart.axisRight.isEnabled = false
        barChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        barChart.xAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val index = value.toInt()
                return if (index in trend.indices) trend[index].date else ""
            }
        }
        barChart.animateY(500)
        chartContainer.addView(barChart)
    }

    /** Backs the row button inside item_sale; without it the "view" icon was a dead tap. */
    private fun showSaleDetails(sale: Sale) {
        val message = buildString {
            append(getString(R.string.label_customer)).append(": ").append(sale.customerName)
            append('\n')
            append(getString(R.string.label_date)).append(": ")
            append(DateFormatter.formatDateTime(sale.saleDate))
            append('\n')
            append(getString(R.string.label_subtotal)).append(": ")
            append(CurrencyFormatter.format(sale.subtotal))
            if (sale.discountAmount > 0) {
                append('\n').append(getString(R.string.label_discount)).append(": ")
                append(CurrencyFormatter.format(sale.discountAmount))
            }
            append('\n').append(getString(R.string.label_tax)).append(": ")
            append(CurrencyFormatter.format(sale.taxAmount))
            append('\n').append(getString(R.string.label_total)).append(": ")
            append(CurrencyFormatter.format(sale.totalAmount))
            append('\n').append(getString(R.string.label_amount_paid)).append(": ")
            append(CurrencyFormatter.format(sale.amountPaid))
            append('\n').append(getString(R.string.label_status)).append(": ")
            append(if (sale.isVoided) getString(R.string.tab_voided) else sale.paymentStatus)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(sale.invoiceNumber)
            .setMessage(message)
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }

    private fun showDatePicker(onSelected: (Int, Int, Int) -> Unit) {
        val cal = java.util.Calendar.getInstance()
        DatePickerDialog(requireContext(), { _, year, month, day ->
            onSelected(year, month, day)
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}