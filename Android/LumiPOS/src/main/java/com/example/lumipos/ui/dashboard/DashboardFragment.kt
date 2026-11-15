package com.example.lumipos.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.lumipos.R
import com.example.lumipos.adapter.LowStockAdapter
import com.example.lumipos.adapter.RecentSaleAdapter
import com.example.lumipos.data.local.entity.Product
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.util.CurrencyFormatter
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry

class DashboardFragment : Fragment() {

    private val viewModel: DashboardViewModel by viewModels()
    private lateinit var recentSalesAdapter: RecentSaleAdapter
    private lateinit var lowStockAdapter: LowStockAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout

    /**
     * Each of the six `stat_*` ids is an <include> of item_stat_card, whose icon and label both
     * start empty — an <include> cannot push attributes into the included layout's children.
     */
    private data class StatCard(val cardId: Int, val labelRes: Int, val iconRes: Int)

    private val statCards = listOf(
        StatCard(R.id.stat_today_sales, R.string.label_today_sales, R.drawable.ic_trending_up),
        StatCard(R.id.stat_today_transactions, R.string.label_transactions, R.drawable.ic_shopping_cart),
        StatCard(R.id.stat_month_sales, R.string.label_month_sales, R.drawable.ic_assessment),
        StatCard(R.id.stat_month_transactions, R.string.label_month_transactions, R.drawable.ic_receipt_long),
        StatCard(R.id.stat_products, R.string.label_total_products, R.drawable.ic_inventory_box),
        StatCard(R.id.stat_inventory_value, R.string.label_inventory_value, R.drawable.ic_local_offer),
        StatCard(R.id.stat_low_stock, R.string.label_low_stock, R.drawable.ic_warning),
        StatCard(R.id.stat_pending_orders, R.string.label_pending_orders, R.drawable.ic_pending_actions),
        StatCard(R.id.stat_expiring_soon, R.string.label_expiring_soon, R.drawable.ic_warning),
        StatCard(R.id.stat_expired, R.string.label_expired, R.drawable.ic_warning)
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        swipeRefresh.setOnRefreshListener { viewModel.refreshData() }

        recentSalesAdapter = RecentSaleAdapter(emptyList()) { sale ->
            navigateToSaleDetail(sale.id)
        }
        view.findViewById<RecyclerView>(R.id.rv_recent_sales).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = recentSalesAdapter
        }

        lowStockAdapter = LowStockAdapter(emptyList()) { product ->
            navigateToProductEdit(product.id)
        }
        view.findViewById<RecyclerView>(R.id.rv_low_stock).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = lowStockAdapter
        }

        // item_stat_card ships its icon and label EMPTY, so each including screen has to fill
        // them in — otherwise every stat renders as a bare number with no indication of what it is.
        statCards.forEach { card ->
            val cardView = view.findViewById<View>(card.cardId) ?: return@forEach
            cardView.findViewById<TextView>(R.id.tv_stat_label)?.text = getString(card.labelRes)
            cardView.findViewById<ImageView>(R.id.iv_stat_icon)?.setImageResource(card.iconRes)
        }

        viewModel.todayRevenue.observe(viewLifecycleOwner) { value ->
            setStatValue(view, R.id.stat_today_sales, value?.let { CurrencyFormatter.format(it) } ?: CurrencyFormatter.format(0.0))
        }
        viewModel.todayTransactions.observe(viewLifecycleOwner) { value ->
            setStatValue(view, R.id.stat_today_transactions, value.toString())
        }
        viewModel.monthRevenue.observe(viewLifecycleOwner) { value ->
            setStatValue(view, R.id.stat_month_sales, value?.let { CurrencyFormatter.format(it) } ?: CurrencyFormatter.format(0.0))
        }
        viewModel.monthTransactions.observe(viewLifecycleOwner) { value ->
            setStatValue(view, R.id.stat_month_transactions, value.toString())
        }
        viewModel.lowStockCount.observe(viewLifecycleOwner) { value ->
            setStatValue(view, R.id.stat_low_stock, value.toString())
        }
        viewModel.totalProducts.observe(viewLifecycleOwner) { value ->
            setStatValue(view, R.id.stat_products, value.toString())
        }
        viewModel.totalInventoryValue.observe(viewLifecycleOwner) { value ->
            setStatValue(view, R.id.stat_inventory_value, value?.let { CurrencyFormatter.format(it) } ?: CurrencyFormatter.format(0.0))
        }

        viewModel.pendingOrders.observe(viewLifecycleOwner) { value ->
            setStatValue(view, R.id.stat_pending_orders, value.toString())
        }

        viewModel.expiringSoonCount.observe(viewLifecycleOwner) { value ->
            setStatValue(view, R.id.stat_expiring_soon, value.toString())
        }

        viewModel.expiredCount.observe(viewLifecycleOwner) { value ->
            setStatValue(view, R.id.stat_expired, value.toString())
        }

        viewModel.recentSales.observe(viewLifecycleOwner) { sales ->
            recentSalesAdapter.submitList(sales)
            view.findViewById<View>(R.id.tv_no_recent_sales).visibility =
                if (sales.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.lowStockProducts.observe(viewLifecycleOwner) { products ->
            lowStockAdapter.submitList(products)
            view.findViewById<View>(R.id.tv_no_low_stock).visibility =
                if (products.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.dailySalesData.observe(viewLifecycleOwner) { data ->
            setupBarChart(view, data)
            // refreshData() re-runs the weekly query that feeds this LiveData, so this emission
            // is the completion signal for a pull-to-refresh. Without it the spinner would hang.
            swipeRefresh.isRefreshing = false
        }
    }

    /**
     * The six `stat_*` ids are attached to `<include layout="@layout/item_stat_card">` tags, so
     * `findViewById(R.id.stat_x)` resolves to the included root (a MaterialCardView) — never a
     * TextView. Casting it to TextView throws ClassCastException at runtime, which crashed the
     * app on launch because DashboardFragment is the nav graph's start destination.
     * Resolve the card first, then read the TextView inside it.
     */
    private fun setStatValue(root: View, cardId: Int, value: String) {
        root.findViewById<View>(cardId)
            ?.findViewById<TextView>(R.id.tv_stat_value)
            ?.text = value
    }

    private fun setupBarChart(view: View, data: List<DailySalesEntry>) {
        val chartContainer = view.findViewById<FrameLayout>(R.id.chart_sales_trend)
        chartContainer.removeAllViews()
        val barChart = BarChart(requireContext())
        barChart.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        val entries = data.mapIndexed { index, entry ->
            BarEntry(index.toFloat(), entry.amount.toFloat())
        }
        val dataSet = BarDataSet(entries, "Daily Sales").apply {
            color = requireContext().getColor(R.color.primary)
            valueTextColor = requireContext().getColor(R.color.text_secondary)
            valueTextSize = 10f
        }
        barChart.data = BarData(dataSet)
        barChart.description.isEnabled = false
        barChart.setTouchEnabled(true)
        barChart.axisRight.isEnabled = false
        barChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        barChart.xAxis.labelCount = data.size
        barChart.xAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val index = value.toInt()
                return if (index in data.indices) data[index].day else ""
            }
        }
        barChart.animateY(500)
        chartContainer.addView(barChart)
    }

    private fun navigateToSaleDetail(saleId: Long) {
        val bundle = Bundle().apply { putLong("saleId", saleId) }
        findNavController().navigate(R.id.saleDetailFragment, bundle)
    }

    private fun navigateToProductEdit(productId: Long) {
        val bundle = Bundle().apply { putLong("productId", productId) }
        findNavController().navigate(R.id.productFormFragment, bundle)
    }
}