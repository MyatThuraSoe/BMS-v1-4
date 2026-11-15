package com.example.lumipos.ui.shift

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.ShiftHistoryAdapter
import com.example.lumipos.data.local.entity.Shift
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter
import com.example.lumipos.util.Extensions.applyStatusTint
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton

class CashShiftFragment : Fragment() {

    private val viewModel: ShiftViewModel by viewModels()
    private lateinit var historyAdapter: ShiftHistoryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_cash_shift, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        historyAdapter = ShiftHistoryAdapter(emptyList())
        view.findViewById<RecyclerView>(R.id.rv_shift_history).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter
        }

        val noShiftCard = view.findViewById<View>(R.id.no_shift_card)
        val activeShiftCard = view.findViewById<View>(R.id.active_shift_card)

        // item_stat_card leaves its icon and label empty, so set them here.
        val statCards = listOf(
            Triple(R.id.card_opening_amount, R.string.label_opening_cash, R.drawable.ic_payments),
            Triple(R.id.card_cash_sales, R.string.label_total_sales, R.drawable.ic_point_of_sale),
            Triple(R.id.card_expected_amount, R.string.label_expected_balance, R.drawable.ic_account_balance)
        )
        statCards.forEach { (cardId, labelRes, iconRes) ->
            val card = view.findViewById<View>(cardId) ?: return@forEach
            card.findViewById<TextView>(R.id.tv_stat_label)?.text = getString(labelRes)
            card.findViewById<ImageView>(R.id.iv_stat_icon)?.setImageResource(iconRes)
        }

        view.findViewById<MaterialButton>(R.id.btn_open_shift).setOnClickListener {
            val amount = view.findViewById<EditText>(R.id.et_opening_amount).text.toString().toDoubleOrNull()
            if (amount == null || amount < 0) {
                toast(getString(R.string.msg_enter_opening_amount))
                return@setOnClickListener
            }
            viewModel.openShift(amount)
            toast(getString(R.string.msg_shift_opened))
        }

        view.findViewById<MaterialButton>(R.id.btn_safe_drop).setOnClickListener {
            val shift = viewModel.currentShift.value ?: return@setOnClickListener
            showSafeDropDialog(shift)
        }

        view.findViewById<MaterialButton>(R.id.btn_close_shift).setOnClickListener {
            val shift = viewModel.currentShift.value ?: return@setOnClickListener
            showCloseShiftDialog(shift)
        }

        viewModel.currentShift.observe(viewLifecycleOwner) { shift ->
            if (shift == null) {
                noShiftCard.visibility = View.VISIBLE
                activeShiftCard.visibility = View.GONE
            } else {
                noShiftCard.visibility = View.GONE
                activeShiftCard.visibility = View.VISIBLE

                view.findViewById<TextView>(R.id.tv_shift_status).apply {
                    text = getString(R.string.label_shift_open)
                    applyStatusTint("OPEN")
                }
                view.findViewById<TextView>(R.id.tv_shift_opened_at).text =
                    getString(R.string.format_opened_at, DateFormatter.formatDateTime(shift.openingTime))

                updateStatCard(view, 0, CurrencyFormatter.format(shift.openingAmount))
                viewModel.loadShiftCashSales(shift.id)
                viewModel.loadSafeDropTotal(shift.id)
            }
        }

        viewModel.shiftCashSales.observe(viewLifecycleOwner) { cashSales ->
            val shift = viewModel.currentShift.value ?: return@observe
            val drops = viewModel.safeDropTotal.value ?: 0.0
            val expected = shift.openingAmount + cashSales - drops
            updateStatCard(view, 1, CurrencyFormatter.format(cashSales))
            updateStatCard(view, 2, CurrencyFormatter.format(expected))
        }

        viewModel.safeDropTotal.observe(viewLifecycleOwner) { drops ->
            val shift = viewModel.currentShift.value ?: return@observe
            val cashSales = viewModel.shiftCashSales.value ?: 0.0
            val expected = shift.openingAmount + cashSales - drops
            updateStatCard(view, 2, CurrencyFormatter.format(expected))
        }

        viewModel.safeDropRecorded.observe(viewLifecycleOwner) { recorded ->
            if (recorded == true) toast(getString(R.string.msg_safe_drop_recorded))
        }

        viewModel.allShifts.observe(viewLifecycleOwner) { shifts ->
            historyAdapter.submitList(shifts)
        }

        viewModel.shiftClosed.observe(viewLifecycleOwner) { closed ->
            if (closed == true) {
                toast(getString(R.string.msg_shift_closed))
            }
        }
    }

    private fun updateStatCard(view: View, index: Int, value: String) {
        val statsRow = view.findViewById<LinearLayout>(R.id.stats_row)
        if (index < statsRow.childCount) {
            statsRow.getChildAt(index).findViewById<TextView>(R.id.tv_stat_value)?.text = value
        }
    }

    /**
     * Blind close-out: the cashier sees only a hint to count the drawer, never the expected total
     * or the variance. Both are computed and stored server-side when the shift is closed.
     */
    private fun showCloseShiftDialog(shift: Shift) {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_close_shift, null)

        dialogView.findViewById<TextView>(R.id.tv_close_shift_info).text =
            getString(R.string.msg_blind_close_hint)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_confirm_close_shift)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_close_shift) { _, _ ->
                val closingAmount =
                    dialogView.findViewById<EditText>(R.id.et_closing_amount).text.toString().toDoubleOrNull()
                if (closingAmount == null || closingAmount < 0) {
                    toast(getString(R.string.msg_enter_closing_amount))
                    showCloseShiftDialog(shift)
                    return@setPositiveButton
                }
                viewModel.closeShift(shift.id, closingAmount, "")
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showSafeDropDialog(shift: Shift) {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_safe_drop, null)

        dialogView.findViewById<TextView>(R.id.tv_safe_drop_total).text = getString(
            R.string.format_safe_drops_total,
            CurrencyFormatter.format(viewModel.safeDropTotal.value ?: 0.0)
        )

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.btn_safe_drop)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val amount = dialogView.findViewById<EditText>(R.id.et_safe_drop_amount).text.toString().toDoubleOrNull()
                val reason = dialogView.findViewById<EditText>(R.id.et_safe_drop_reason).text.toString().trim()
                if (amount == null || amount <= 0.0) {
                    toast(getString(R.string.msg_enter_amount))
                    return@setPositiveButton
                }
                viewModel.recordSafeDrop(shift.id, amount, reason)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}