package com.example.lumipos.ui.expenses

import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Expense
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter
import com.example.lumipos.util.ExpenseCategories
import com.example.lumipos.util.Extensions.toast
import com.example.lumipos.util.ImageUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar

class ExpenseFormFragment : Fragment() {

    private val viewModel: ExpenseFormViewModel by viewModels()

    private var editingExpenseId: Long = -1L

    /**
     * The row being edited, once loaded. Held so the save can `copy()` it - that is what keeps
     * `id` and `createdAt` intact. Building a fresh `Expense` instead would insert a duplicate
     * (autoGenerate id 0) and reset the creation stamp on every edit.
     */
    private var loadedExpense: Expense? = null

    private var selectedCategory: String = ExpenseCategories.ALL.last()
    private var receiptBytes: ByteArray? = null
    private var expenseDate: Long = System.currentTimeMillis()

    private val pickReceiptLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@registerForActivityResult
            loadReceipt(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_expense_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editingExpenseId = arguments?.getLong("expenseId", -1L) ?: -1L
        if (editingExpenseId > 0) {
            viewModel.loadExpense(editingExpenseId)
        }

        val descriptionLayout = view.findViewById<TextInputLayout>(R.id.til_expense_description)
        val amountLayout = view.findViewById<TextInputLayout>(R.id.til_expense_amount)

        // The layout ships a neutral hint; show the shop's real currency instead.
        amountLayout.prefixText = CurrencyFormatter.symbol()

        setupCategoryDropdown(view)
        setupDateField(view)
        setupReceipt(view)

        view.findViewById<MaterialButton>(R.id.btn_save_expense).setOnClickListener {
            descriptionLayout.error = null
            amountLayout.error = null

            val description = view.findViewById<TextInputEditText>(R.id.et_expense_description)
                .text.toString().trim()
            val amount = view.findViewById<TextInputEditText>(R.id.et_expense_amount)
                .text.toString().trim().toDoubleOrNull()
            val tax = view.findViewById<TextInputEditText>(R.id.et_expense_tax)
                .text.toString().trim().toDoubleOrNull() ?: 0.0

            // Inline errors on the fields rather than a toast that vanishes.
            if (description.isBlank()) {
                descriptionLayout.error = getString(R.string.msg_expense_description_required)
                return@setOnClickListener
            }
            if (amount == null || amount <= 0.0) {
                amountLayout.error = getString(R.string.msg_expense_amount_required)
                return@setOnClickListener
            }

            // Falls back to an Expense carrying editingExpenseId so saving before the load
            // completes still updates the right row instead of inserting a second one.
            val base = loadedExpense ?: Expense(
                id = editingExpenseId.coerceAtLeast(0L),
                description = description,
                amount = amount
            )

            viewModel.saveExpense(
                base.copy(
                    description = description,
                    amount = amount,
                    taxAmount = tax.coerceAtLeast(0.0),
                    category = selectedCategory,
                    expenseDate = expenseDate,
                    receiptImageBytes = receiptBytes
                )
            )
        }

        viewModel.expense.observe(viewLifecycleOwner) { expense ->
            expense ?: return@observe
            loadedExpense = expense

            view.findViewById<TextInputEditText>(R.id.et_expense_description).setText(expense.description)
            view.findViewById<TextInputEditText>(R.id.et_expense_amount)
                .setText(if (expense.amount == 0.0) "" else expense.amount.toString())
            view.findViewById<TextInputEditText>(R.id.et_expense_tax)
                .setText(if (expense.taxAmount == 0.0) "" else expense.taxAmount.toString())

            selectedCategory = expense.category.ifBlank { ExpenseCategories.ALL.last() }
            view.findViewById<AutoCompleteTextView>(R.id.actv_expense_category)
                .setText(ExpenseCategories.label(requireContext(), selectedCategory), false)

            expenseDate = expense.expenseDate
            view.findViewById<TextInputEditText>(R.id.et_expense_date)
                .setText(DateFormatter.formatDate(expense.expenseDate))

            receiptBytes = expense.receiptImageBytes
            renderReceipt(view, expense.receiptImageBytes)
        }

        viewModel.saveSuccess.observe(viewLifecycleOwner) { success ->
            if (success == true) {
                toast(getString(R.string.msg_expense_saved))
                findNavController().popBackStack()
            }
        }
    }

    private fun setupCategoryDropdown(view: View) {
        val labels = ExpenseCategories.ALL.map { ExpenseCategories.label(requireContext(), it) }
        val categoryView = view.findViewById<AutoCompleteTextView>(R.id.actv_expense_category)
        categoryView.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        )
        // Pre-select so a brand-new expense never saves an empty category.
        categoryView.setText(ExpenseCategories.label(requireContext(), selectedCategory), false)
        categoryView.setOnItemClickListener { _, _, position, _ ->
            selectedCategory = ExpenseCategories.ALL[position]
        }
    }

    private fun setupDateField(view: View) {
        val dateField = view.findViewById<TextInputEditText>(R.id.et_expense_date)
        dateField.setText(DateFormatter.formatDate(expenseDate))

        fun pickDate() {
            val cal = Calendar.getInstance()
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val picked = DateFormatter.toStartOfDay(year, month, day)
                    expenseDate = picked
                    dateField.setText(DateFormatter.formatDate(picked))
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        dateField.setOnClickListener { pickDate() }
        // `endIconMode="custom"` does not wire a click by itself, so do it here.
        view.findViewById<TextInputLayout>(R.id.til_expense_date)
            .setEndIconOnClickListener { pickDate() }
    }

    private fun setupReceipt(view: View) {
        view.findViewById<MaterialButton>(R.id.btn_pick_receipt).setOnClickListener {
            pickReceiptLauncher.launch("image/*")
        }
        view.findViewById<MaterialButton>(R.id.btn_remove_receipt).setOnClickListener {
            receiptBytes = null
            renderReceipt(view, null)
        }
    }

    private fun loadReceipt(uri: Uri) {
        val scaled = ImageUtils.loadScaledBitmap(requireContext(), uri, 1024)
        if (scaled == null) {
            toast(getString(R.string.msg_receipt_unreadable))
            return
        }
        receiptBytes = ImageUtils.toBytes(scaled, Bitmap.CompressFormat.JPEG, 80)
        // The picker result can arrive after the view is gone (e.g. the user rotated while the
        // gallery was open), so the fragment's nullable `view` is used rather than assumed.
        view?.let { renderReceipt(it, receiptBytes) }
    }

    private fun renderReceipt(view: View, bytes: ByteArray?) {
        val preview = view.findViewById<ImageView>(R.id.iv_receipt_preview)
        val removeButton = view.findViewById<MaterialButton>(R.id.btn_remove_receipt)

        if (bytes == null) {
            preview.visibility = View.GONE
            removeButton.visibility = View.GONE
        } else {
            preview.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            preview.visibility = View.VISIBLE
            removeButton.visibility = View.VISIBLE
        }
    }
}
