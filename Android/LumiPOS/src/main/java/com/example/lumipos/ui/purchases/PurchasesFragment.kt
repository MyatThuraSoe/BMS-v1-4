package com.example.lumipos.ui.purchases

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.PurchaseAdapter
import com.example.lumipos.data.local.entity.Purchase
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton

class PurchasesFragment : Fragment() {

    private val viewModel: PurchasesViewModel by viewModels()
    private lateinit var adapter: PurchaseAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_purchases, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = PurchaseAdapter(
            emptyList(),
            onPaymentStatusClick = { purchase -> showPaymentStatusDialog(purchase) }
        )
        view.findViewById<RecyclerView>(R.id.rv_purchases).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = this@PurchasesFragment.adapter
        }

        view.findViewById<MaterialButton>(R.id.btn_new_purchase).setOnClickListener {
            findNavController().navigate(R.id.purchaseFormFragment)
        }

        viewModel.allPurchases.observe(viewLifecycleOwner) { purchases ->
            adapter.submitList(purchases)
        }
    }

    private fun showPaymentStatusDialog(purchase: Purchase) {
        val statuses = arrayOf("PENDING", "PARTIAL", "PAID")
        var selected = purchase.paymentStatus

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.msg_payment_status_title, purchase.purchaseNumber))
            .setSingleChoiceItems(statuses, statuses.indexOf(selected).coerceAtLeast(0)) { _, which ->
                selected = statuses[which]
            }
            .setPositiveButton(R.string.btn_save) { _, _ ->
                viewModel.updatePaymentStatus(purchase.id, selected)
                toast(getString(R.string.msg_payment_status_updated))
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}