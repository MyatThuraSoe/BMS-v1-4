package com.example.lumipos.ui.expenses

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.ExpenseAdapter
import com.example.lumipos.data.local.entity.Expense
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.ExpenseCategories
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton

class ExpensesFragment : Fragment() {

    private val viewModel: ExpensesViewModel by viewModels()
    private lateinit var adapter: ExpenseAdapter

    /** Display label paired with the period it selects, so the dropdown and the filter agree. */
    private val periodOptions = listOf(
        ExpensePeriod.THIS_MONTH to R.string.period_this_month,
        ExpensePeriod.THIS_YEAR to R.string.period_this_year,
        ExpensePeriod.ALL_TIME to R.string.period_all_time
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_expenses, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ExpenseAdapter(
            onEdit = { expense -> navigateToExpenseForm(expense.id) },
            onDelete = { expense -> showDeleteDialog(expense) }
        )
        view.findViewById<RecyclerView>(R.id.rv_expenses).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ExpensesFragment.adapter
        }

        view.findViewById<MaterialButton>(R.id.btn_add_expense).setOnClickListener {
            navigateToExpenseForm(-1L)
        }

        // item_stat_card ships with its icon and label EMPTY - an <include> cannot push attributes
        // into the included layout's children, so each screen that includes it fills both itself.
        val totalCard = view.findViewById<View>(R.id.card_total_expenses)
        totalCard?.findViewById<TextView>(R.id.tv_stat_label)?.text =
            getString(R.string.label_total_expenses)
        totalCard?.findViewById<ImageView>(R.id.iv_stat_icon)?.setImageResource(R.drawable.ic_trending_down)

        setupPeriodDropdown(view)
        setupCategoryDropdown(view)

        viewModel.expenses.observe(viewLifecycleOwner) { expenses ->
            adapter.submitList(expenses)
            view.findViewById<TextView>(R.id.tv_expenses_empty).visibility =
                if (expenses.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.total.observe(viewLifecycleOwner) { total ->
            // Found via the card id rather than stats_row.getChildAt(0): an index into a
            // container that might be empty fails silently, which is how several stat rows in
            // this app previously rendered nothing at all.
            totalCard?.findViewById<TextView>(R.id.tv_stat_value)?.text =
                CurrencyFormatter.format(total)
        }
    }

    private fun setupPeriodDropdown(view: View) {
        val labels = periodOptions.map { getString(it.second) }
        val periodView = view.findViewById<AutoCompleteTextView>(R.id.actv_expense_period)
        periodView.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        )
        periodView.setText(labels[0], false)
        periodView.setOnItemClickListener { _, _, position, _ ->
            viewModel.setPeriod(periodOptions[position].first)
        }

        // Observed as well as driven: keeps the shown label correct if the ViewModel's period
        // outlives this view (rotation, back-stack return).
        viewModel.period.observe(viewLifecycleOwner) { period ->
            val index = periodOptions.indexOfFirst { it.first == period }
            if (index >= 0 && periodView.text.toString() != labels[index]) {
                periodView.setText(labels[index], false)
            }
        }
    }

    private fun setupCategoryDropdown(view: View) {
        val allLabel = getString(R.string.label_all_categories)
        val labels = listOf(allLabel) +
            ExpenseCategories.ALL.map { ExpenseCategories.label(requireContext(), it) }

        val categoryView = view.findViewById<AutoCompleteTextView>(R.id.actv_expense_category_filter)
        categoryView.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        )
        categoryView.setText(allLabel, false)
        categoryView.setOnItemClickListener { _, _, position, _ ->
            // Position 0 is "All categories"; the rest are offset by that one entry.
            viewModel.setCategory(if (position == 0) null else ExpenseCategories.ALL[position - 1])
        }

        viewModel.category.observe(viewLifecycleOwner) { category ->
            val index = if (category == null) 0 else ExpenseCategories.ALL.indexOf(category) + 1
            if (index in labels.indices && categoryView.text.toString() != labels[index]) {
                categoryView.setText(labels[index], false)
            }
        }
    }

    private fun navigateToExpenseForm(expenseId: Long) {
        val bundle = Bundle().apply { putLong("expenseId", expenseId) }
        findNavController().navigate(R.id.expenseFormFragment, bundle)
    }

    private fun showDeleteDialog(expense: Expense) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_confirm_delete)
            .setMessage(getString(R.string.msg_delete_expense_confirm, expense.description))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                viewModel.deleteExpense(expense)
                toast(getString(R.string.msg_expense_deleted))
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
