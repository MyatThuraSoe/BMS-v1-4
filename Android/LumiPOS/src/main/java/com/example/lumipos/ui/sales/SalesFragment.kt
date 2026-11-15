package com.example.lumipos.ui.sales

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.SaleAdapter
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.util.Extensions.toast
import com.example.lumipos.util.DateFormatter
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SalesFragment : Fragment() {

    private val viewModel: SalesViewModel by viewModels()
    private lateinit var adapter: SaleAdapter

    /** Set by setupRangeChips so the custom-date path can clear the preset highlight. */
    private var highlightRangeChip: ((String?) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_sales, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SaleAdapter { sale ->
            val bundle = Bundle().apply { putLong("saleId", sale.id) }
            findNavController().navigate(R.id.saleDetailFragment, bundle)
        }
        view.findViewById<RecyclerView>(R.id.rv_sales).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = this@SalesFragment.adapter
        }

        val rangeChips = view.findViewById<LinearLayout>(R.id.range_chips)
        setupRangeChips(rangeChips)

        view.findViewById<MaterialButton>(R.id.btn_date_range).setOnClickListener {
            showCustomDateDialog()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pagedSales.collectLatest { paged ->
                adapter.submitData(paged)
            }
        }
    }

    /** A preset date range shown as a chip. A null start/end means "no filter". */
    private data class RangeOption(val key: String, val label: String, val start: Long?, val end: Long?)

    private fun setupRangeChips(container: LinearLayout) {
        val options = listOf(
            RangeOption("all", getString(R.string.tab_all), null, null),
            RangeOption("today", getString(R.string.tab_today),
                DateFormatter.getTodayStart(), DateFormatter.getTodayEnd()),
            RangeOption("week", getString(R.string.tab_week),
                DateFormatter.getWeekStart(), DateFormatter.getTodayEnd()),
            RangeOption("month", getString(R.string.tab_month),
                DateFormatter.getMonthStart(), DateFormatter.getTodayEnd()),
            RangeOption("year", getString(R.string.tab_year),
                DateFormatter.getYearStart(), DateFormatter.getTodayEnd())
        )

        val chips = mutableListOf<Pair<MaterialButton, String>>()

        // The chips were indistinguishable, so the active range was invisible. The custom-date
        // path clears the highlight by passing null.
        val highlight: (String?) -> Unit = { activeKey ->
            chips.forEach { (chip, key) ->
                val active = key == activeKey
                chip.backgroundTintList = ColorStateList.valueOf(
                    requireContext().getColor(if (active) R.color.primary else R.color.surface)
                )
                chip.setTextColor(
                    requireContext().getColor(if (active) R.color.on_primary else R.color.primary)
                )
            }
        }
        highlightRangeChip = highlight

        for (option in options) {
            val chip = MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = option.label
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8.dp }
                setOnClickListener {
                    viewModel.filterByDateRange(option.start, option.end)
                    highlight(option.key)
                }
            }
            chips.add(chip to option.key)
            container.addView(chip)
        }

        highlight("all")
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun showCustomDateDialog() {
        val cal = java.util.Calendar.getInstance()
        DatePickerDialog(requireContext(), { _, year, month, day ->
            val start = DateFormatter.toStartOfDay(year, month, day)
            DatePickerDialog(requireContext(), { _, y2, m2, d2 ->
                val end = DateFormatter.toEndOfDay(y2, m2, d2)
                viewModel.filterByDateRange(start, end)
                // No preset chip matches a hand-picked range.
                highlightRangeChip?.invoke(null)
            }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }
}