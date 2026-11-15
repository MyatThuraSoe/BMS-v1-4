package com.example.lumipos.ui.transfers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.TransferLineAdapter
import com.example.lumipos.data.local.entity.Product
import com.example.lumipos.data.local.entity.StockTransferItem
import com.example.lumipos.util.Extensions.applyStatusTint
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class StockTransferFormFragment : Fragment() {

    private val viewModel: StockTransferFormViewModel by viewModels()
    private lateinit var adapter: TransferLineAdapter
    private val workingLines = mutableListOf<StockTransferItem>()
    private var products: List<Product> = emptyList()
    private var isNew = true
    private var currentStatus = "DRAFT"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_stock_transfer_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val transferId = arguments?.getLong("transferId", -1L) ?: -1L
        isNew = transferId <= 0

        val etFrom = view.findViewById<TextInputEditText>(R.id.et_from)
        val etTo = view.findViewById<TextInputEditText>(R.id.et_to)
        val etNotes = view.findViewById<TextInputEditText>(R.id.et_notes)
        val llHeader = view.findViewById<LinearLayout>(R.id.ll_transfer_header)
        val tvHeader = view.findViewById<TextView>(R.id.tv_transfer_header)
        val tvStatus = view.findViewById<TextView>(R.id.tv_transfer_status)
        val llAddLine = view.findViewById<LinearLayout>(R.id.ll_add_line)
        val spProduct = view.findViewById<Spinner>(R.id.sp_transfer_product)
        val etQty = view.findViewById<EditText>(R.id.et_line_qty)
        val btnAddLine = view.findViewById<MaterialButton>(R.id.btn_add_line)
        val btnAction = view.findViewById<MaterialButton>(R.id.btn_transfer_action)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btn_transfer_cancel)

        adapter = TransferLineAdapter { item ->
            workingLines.removeAll { it.productId == item.productId && it.productName == item.productName }
            adapter.submitList(workingLines.toList())
        }
        val rv = view.findViewById<RecyclerView>(R.id.rv_transfer_lines)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        viewModel.products.observe(viewLifecycleOwner) { list ->
            products = list
            spProduct.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                list.map { it.name }
            )
        }

        if (isNew) {
            llHeader.visibility = View.GONE
            llAddLine.visibility = View.VISIBLE
            btnAction.text = getString(R.string.btn_save_draft)
            btnCancel.visibility = View.GONE
            etFrom.isEnabled = true
            etTo.isEnabled = true
            etNotes.isEnabled = true

            btnAddLine.setOnClickListener {
                val idx = spProduct.selectedItemPosition
                if (idx < 0 || idx >= products.size) return@setOnClickListener
                val qty = etQty.text.toString().toIntOrNull() ?: 0
                if (qty < 1) {
                    toast(getString(R.string.msg_component_qty_invalid))
                    return@setOnClickListener
                }
                val product = products[idx]
                val existing = workingLines.find { it.productId == product.id }
                if (existing != null) {
                    val pos = workingLines.indexOf(existing)
                    workingLines[pos] = existing.copy(quantity = existing.quantity + qty)
                } else {
                    workingLines.add(
                        StockTransferItem(
                            transferId = 0,
                            productId = product.id,
                            productName = product.name,
                            sku = product.sku,
                            quantity = qty
                        )
                    )
                }
                etQty.setText("")
                adapter.submitList(workingLines.toList())
            }

            btnAction.setOnClickListener {
                val from = etFrom.text.toString().trim()
                val to = etTo.text.toString().trim()
                val notes = etNotes.text.toString().trim()
                if (from.isBlank() || to.isBlank() || workingLines.isEmpty()) {
                    toast(getString(R.string.msg_transfer_required))
                    return@setOnClickListener
                }
                viewModel.createDraft(from, to, notes, workingLines.toList())
            }

            viewModel.createdId.observe(viewLifecycleOwner) { id ->
                if (id != null) {
                    toast(getString(R.string.msg_transfer_created))
                    viewModel.consumedCreatedId()
                    findNavController().popBackStack()
                }
            }
        } else {
            llHeader.visibility = View.VISIBLE
            llAddLine.visibility = View.GONE
            btnCancel.visibility = View.GONE
            etFrom.isEnabled = false
            etTo.isEnabled = false
            etNotes.isEnabled = false
            viewModel.load(transferId)

            viewModel.transfer.observe(viewLifecycleOwner) { transfer ->
                transfer ?: return@observe
                currentStatus = transfer.status
                tvHeader.text = transfer.transferNumber
                tvStatus.text = transfer.status
                tvStatus.applyStatusTint(transfer.status)
                etFrom.setText(transfer.fromLocation)
                etTo.setText(transfer.toLocation)
                etNotes.setText(transfer.notes)

                btnAction.visibility = View.VISIBLE
                btnCancel.visibility = View.VISIBLE
                when (transfer.status) {
                    "DRAFT" -> {
                        btnAction.text = getString(R.string.btn_send_transfer)
                        btnCancel.visibility = View.VISIBLE
                    }
                    "SENT" -> {
                        btnAction.text = getString(R.string.btn_receive_transfer)
                        btnCancel.visibility = View.GONE
                    }
                    else -> {
                        btnAction.visibility = View.GONE
                        btnCancel.visibility = View.GONE
                    }
                }
            }

            viewModel.items.observe(viewLifecycleOwner) { items ->
                adapter.submitList(items)
            }

            btnAction.setOnClickListener {
                when (currentStatus) {
                    "DRAFT" -> confirmAction(
                        R.string.msg_confirm_send_transfer,
                        R.string.btn_send_transfer
                    ) { viewModel.send() }
                    "SENT" -> confirmAction(
                        R.string.msg_confirm_receive_transfer,
                        R.string.btn_receive_transfer
                    ) { viewModel.receive() }
                }
            }

            btnCancel.setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setMessage(R.string.btn_cancel_transfer)
                    .setPositiveButton(R.string.btn_cancel_transfer) { _, _ -> viewModel.cancel() }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }

            viewModel.actionDone.observe(viewLifecycleOwner) { done ->
                if (done == true) {
                    val msg = when (currentStatus) {
                        "SENT" -> R.string.msg_transfer_sent
                        "RECEIVED" -> R.string.msg_transfer_received
                        else -> R.string.msg_transfer_cancelled
                    }
                    toast(getString(msg))
                    findNavController().popBackStack()
                }
            }
        }
    }

    private fun confirmAction(messageRes: Int, positiveRes: Int, action: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setMessage(messageRes)
            .setPositiveButton(positiveRes) { _, _ -> action() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
