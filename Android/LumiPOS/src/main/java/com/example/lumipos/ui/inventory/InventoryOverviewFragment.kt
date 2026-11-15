package com.example.lumipos.ui.inventory

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.LowStockAdapter
import com.example.lumipos.util.CurrencyFormatter
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry

class InventoryOverviewFragment : Fragment() {

    private val viewModel: InventoryViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_inventory_overview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val statsRow1 = view.findViewById<LinearLayout>(R.id.stats_row_1)
        val statsRow2 = view.findViewById<LinearLayout>(R.id.stats_row_2)

        // item_stat_card ships its icon and label EMPTY, so each including screen must fill
        // them in — otherwise the stats render as bare numbers.
        setStatCard(statsRow1, 0, R.string.label_total_products, R.drawable.ic_inventory_box)
        setStatCard(statsRow1, 1, R.string.label_inventory_value, R.drawable.ic_local_offer)
        setStatCard(statsRow2, 0, R.string.label_low_stock, R.drawable.ic_warning)
        setStatCard(statsRow2, 1, R.string.label_out_of_stock, R.drawable.ic_block)

        viewModel.totalProducts.observe(viewLifecycleOwner) { value ->
            setStat(statsRow1, 0, value?.toString() ?: "0")
        }
        viewModel.totalInventoryValue.observe(viewLifecycleOwner) { value ->
            setStat(statsRow1, 1, value?.let { CurrencyFormatter.format(it) } ?: CurrencyFormatter.format(0.0))
        }
        viewModel.lowStockCount.observe(viewLifecycleOwner) { value ->
            setStat(statsRow2, 0, value?.toString() ?: "0")
        }
        viewModel.outOfStockCount.observe(viewLifecycleOwner) { value ->
            setStat(statsRow2, 1, value?.toString() ?: "0")
        }

        viewModel.allMovements.observe(viewLifecycleOwner) { movements ->
            setupMovementChart(view, movements.takeLast(14))
        }
    }

    private fun setStat(container: LinearLayout, index: Int, value: String) {
        if (index < container.childCount) {
            container.getChildAt(index).findViewById<TextView>(R.id.tv_stat_value)?.text = value
        }
    }

    private fun setStatCard(container: LinearLayout, index: Int, labelRes: Int, iconRes: Int) {
        if (index >= container.childCount) return
        val card = container.getChildAt(index)
        card.findViewById<TextView>(R.id.tv_stat_label)?.text = getString(labelRes)
        card.findViewById<ImageView>(R.id.iv_stat_icon)?.setImageResource(iconRes)
    }

    private fun setupMovementChart(view: View, movements: List<com.example.lumipos.data.local.entity.StockMovement>) {
        val chartContainer = view.findViewById<FrameLayout>(R.id.chart_movement_trend)
        chartContainer.removeAllViews()
        val barChart = BarChart(requireContext())
        barChart.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        val grouped = movements.groupBy { com.example.lumipos.util.DateFormatter.formatDateShort(it.movementDate) }
        val labels = grouped.keys.toList()
        val entries = mutableListOf<BarEntry>()
        grouped.values.forEachIndexed { index, list ->
            val inQty = list.filter { it.movementType == "IN" || it.movementType == "ADJUSTMENT_IN" }.sumOf { it.quantity }
            val outQty = list.filter { it.movementType == "OUT" || it.movementType == "ADJUSTMENT_OUT" }.sumOf { it.quantity }.toDouble()
            entries.add(BarEntry(index.toFloat(), floatArrayOf(inQty.toFloat(), outQty.toFloat())))
        }

        val dataSet = BarDataSet(entries, "Stock Movements").apply {
            colors = listOf(
                requireContext().getColor(R.color.status_paid),
                requireContext().getColor(R.color.status_void)
            )
            valueTextSize = 9f
        }

        barChart.data = BarData(dataSet).apply { barWidth = 0.5f }
        barChart.description.isEnabled = false
        barChart.axisRight.isEnabled = false
        barChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        barChart.xAxis.granularity = 1f
        barChart.xAxis.labelCount = labels.size
        barChart.xAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val index = value.toInt()
                return if (index in labels.indices) labels[index] else ""
            }
        }

        // groupBars() rewrites the bar positions, so the chart has to be told to rebuild
        // afterwards - without this the grouped bars never render.
        barChart.groupBars(0f, 0.3f, 0.06f)
        barChart.notifyDataSetChanged()
        barChart.animateY(500)
        barChart.invalidate()
        chartContainer.addView(barChart)
    }
}