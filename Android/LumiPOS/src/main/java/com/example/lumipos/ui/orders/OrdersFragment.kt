package com.example.lumipos.ui.orders

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.OrderAdapter
import com.example.lumipos.adapter.OrderItemAdapter
import com.example.lumipos.data.local.entity.Order
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar

class OrdersFragment : Fragment() {

    private val viewModel: OrdersViewModel by viewModels()
    private lateinit var adapter: OrderAdapter
    private lateinit var orderItemAdapter: OrderItemAdapter

    /** Set by setupStatusChips so the statusFilter observer can repaint the chips. */
    private var highlightStatusChip: ((String?) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_orders, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = OrderAdapter(emptyList()) { order ->
            showOrderDetailDialog(order)
        }
        view.findViewById<RecyclerView>(R.id.rv_orders).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@OrdersFragment.adapter
        }

        // Used to be a toast telling the cashier to go and find the POS screen. Take them there.
        view.findViewById<MaterialButton>(R.id.btn_new_order).setOnClickListener {
            findNavController().navigate(R.id.posFragment)
        }

        val statusChips = view.findViewById<android.widget.LinearLayout>(R.id.status_chips)
        setupStatusChips(statusChips)
        viewModel.statusFilter.observe(viewLifecycleOwner) { active ->
            highlightStatusChip?.invoke(active)
        }

        viewModel.filteredOrders.observe(viewLifecycleOwner) { orders ->
            adapter.submitList(orders)
        }

        // Line items for the order-detail dialog. Observed once here rather than inside the
        // dialog builder, which would stack a new observer on every open.
        orderItemAdapter = OrderItemAdapter()
        viewModel.orderItems.observe(viewLifecycleOwner) { items ->
            orderItemAdapter.submitList(items)
        }

        // Without this the list kept showing the order as PENDING after a successful conversion.
        viewModel.convertSuccess.observe(viewLifecycleOwner) { success ->
            if (success == true) {
                toast(getString(R.string.msg_order_converted))
                viewModel.loadOrders()
                viewModel.clearConvertSuccess()
            }
        }
    }

    private fun setupStatusChips(container: android.widget.LinearLayout) {
        // Keyed by the value the ViewModel filters on; the label is a string resource.
        val statuses = listOf(
            "ALL" to getString(R.string.tab_all),
            "PENDING" to getString(R.string.tab_pending),
            "CONVERTED" to getString(R.string.tab_converted),
            "CANCELLED" to getString(R.string.tab_cancelled)
        )

        val chips = mutableListOf<Pair<MaterialButton, String>>()
        // The chips all looked alike, so nothing told the cashier which filter was applied.
        val highlight: (String?) -> Unit = { active ->
            chips.forEach { (chip, status) ->
                val selected = status == active
                chip.backgroundTintList = ColorStateList.valueOf(
                    requireContext().getColor(if (selected) R.color.primary else R.color.surface)
                )
                chip.setTextColor(
                    requireContext().getColor(if (selected) R.color.on_primary else R.color.primary)
                )
            }
        }
        highlightStatusChip = highlight

        for ((status, label) in statuses) {
            val chip = MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                isAllCaps = false
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8.dp }
                setOnClickListener { viewModel.filterByStatus(status) }
            }
            chips.add(chip to status)
            container.addView(chip)
        }

        highlight(viewModel.statusFilter.value)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun showOrderDetailDialog(order: Order) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_order_actions, null)

        dialogView.findViewById<TextView>(R.id.tv_order_info).text =
            "${getString(R.string.label_order)}: ${order.orderNumber}\n" +
            "${getString(R.string.label_customer)}: ${order.customerName}\n" +
            "${getString(R.string.label_items)}: ${order.itemCount}\n" +
            "${getString(R.string.label_created)}: ${DateFormatter.formatDateTime(order.createdAt)}\n" +
            "${getString(R.string.label_total)}: ${CurrencyFormatter.format(order.totalAmount)}"

        dialogView.findViewById<RecyclerView>(R.id.rv_order_items).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = orderItemAdapter
        }
        viewModel.loadOrderItems(order.id)

        // This layout carries its own Convert / Cancel / Close buttons, so the AlertDialog must
        // NOT add a second set. It previously did, and the in-body buttons did nothing at all.
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        val isPending = order.status == "PENDING"
        val convertButton = dialogView.findViewById<MaterialButton>(R.id.btn_convert_order)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.btn_cancel_order)

        convertButton.visibility = if (isPending) View.VISIBLE else View.GONE
        cancelButton.visibility = if (isPending) View.VISIBLE else View.GONE

        convertButton.setOnClickListener {
            dialog.dismiss()
            showConvertDialog(order)
        }
        cancelButton.setOnClickListener {
            dialog.dismiss()
            showCancelOrderDialog(order)
        }
        dialogView.findViewById<MaterialButton>(R.id.btn_close)
            .setOnClickListener { dialog.dismiss() }

        // Release the line items on close so the next order does not briefly show the previous
        // order's rows while its own query is still in flight.
        dialog.setOnDismissListener { viewModel.clearOrderItems() }

        dialog.show()
    }

    private fun showConvertDialog(order: Order) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_convert_order, null)

        val paymentGroup = dialogView.findViewById<RadioGroup>(R.id.rg_payment_method)
        val etAmount = dialogView.findViewById<EditText>(R.id.et_amount)
        val dueLayout = dialogView.findViewById<View>(R.id.til_due_date)
        val etDueDate = dialogView.findViewById<EditText>(R.id.et_due_date)

        var dueDateMillis: Long? = null

        fun pickDueDate() {
            val cal = Calendar.getInstance()
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val picked = DateFormatter.toStartOfDay(year, month, day)
                    dueDateMillis = picked
                    etDueDate.setText(DateFormatter.formatDate(picked))
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        etDueDate.setOnClickListener { pickDueDate() }

        // The Cash / Credit choice was rendered but never read — every conversion went through
        // as CASH regardless of what the cashier picked.
        fun applyPaymentMethod() {
            val isCredit = paymentGroup.checkedRadioButtonId == R.id.rb_credit
            dueLayout.visibility = if (isCredit) View.VISIBLE else View.GONE
        }
        paymentGroup.setOnCheckedChangeListener { _, _ -> applyPaymentMethod() }
        applyPaymentMethod()

        etAmount.setText(CurrencyFormatter.formatPlain(order.totalAmount))

        // The layout ships a neutral fallback prefix; show the shop's real currency instead.
        dialogView.findViewById<TextInputLayout>(R.id.til_amount).prefixText =
            CurrencyFormatter.symbol()

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btn_confirm_convert).setOnClickListener {
            val isCredit = paymentGroup.checkedRadioButtonId == R.id.rb_credit
            if (isCredit && dueDateMillis == null) {
                toast(getString(R.string.msg_choose_due_date))
                return@setOnClickListener
            }
            val paid = etAmount.text.toString().toDoubleOrNull() ?: order.totalAmount
            viewModel.convertOrder(
                orderId = order.id,
                paymentMethod = if (isCredit) "CREDIT" else "CASH",
                amountPaid = paid,
                dueDate = if (isCredit) dueDateMillis else null
            )
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showCancelOrderDialog(order: Order) {
        val editText = android.widget.EditText(requireContext())
        editText.hint = "Reason for cancellation"

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.msg_cancel_order_title)
            .setView(editText)
            .setPositiveButton(R.string.btn_cancel) { _, _ ->
                val reason = editText.text.toString()
                if (reason.isBlank()) {
                    toast(getString(R.string.msg_reason_required))
                    return@setPositiveButton
                }
                viewModel.cancelOrder(order.id, reason)
                toast(getString(R.string.msg_order_cancelled))
            }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }
}