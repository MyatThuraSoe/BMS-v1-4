package com.example.lumipos.ui.returns

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.ReturnLineAdapter
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton

class ReturnFormFragment : Fragment() {

    private val viewModel: ReturnFormViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_return_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val saleId = arguments?.getLong("saleId", -1L) ?: -1L
        if (saleId > 0) viewModel.load(saleId)

        val tvTotal = view.findViewById<TextView>(R.id.tv_return_total)
        val etReason = view.findViewById<EditText>(R.id.et_return_reason)
        val spMethod = view.findViewById<Spinner>(R.id.sp_refund_method)
        val rvLines = view.findViewById<RecyclerView>(R.id.rv_return_lines)

        val methods = listOf(
            "CASH" to getString(R.string.payment_cash),
            "CARD" to getString(R.string.payment_card),
            "STORE_CREDIT" to getString(R.string.payment_store_credit)
        )
        spMethod.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            methods.map { it.second }
        )

        rvLines.layoutManager = LinearLayoutManager(requireContext())

        view.findViewById<MaterialButton>(R.id.btn_return_back).setOnClickListener {
            findNavController().popBackStack()
        }

        view.findViewById<MaterialButton>(R.id.btn_process_return).setOnClickListener {
            val reason = etReason.text.toString().trim()
            if (reason.isBlank()) {
                toast(getString(R.string.msg_return_reason_required))
                return@setOnClickListener
            }
            val method = methods[spMethod.selectedItemPosition].first
            viewModel.processReturn(method, reason)
        }

        viewModel.sale.observe(viewLifecycleOwner) { sale ->
            sale ?: return@observe
            view.findViewById<TextView>(R.id.tv_return_invoice).text = sale.invoiceNumber
            view.findViewById<TextView>(R.id.tv_return_customer).text = sale.customerName
        }

        viewModel.lines.observe(viewLifecycleOwner) { lines ->
            // The adapter edits these instances in place, so it gets the ViewModel's own list.
            // onChange recomputes the running refund total from it.
            val mutable = lines.toMutableList()
            rvLines.adapter = ReturnLineAdapter(mutable) {
                tvTotal.text = CurrencyFormatter.format(mutable.sumOf { it.refundAmount })
            }
            tvTotal.text = CurrencyFormatter.format(0.0)
        }

        viewModel.done.observe(viewLifecycleOwner) { done ->
            if (done) {
                toast(getString(R.string.msg_return_done))
                findNavController().popBackStack()
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let { toast(it) }
        }
    }
}
