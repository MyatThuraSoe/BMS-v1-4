package com.example.lumipos.ui.transfers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.StockTransferAdapter
import com.example.lumipos.data.local.entity.StockTransfer
import com.google.android.material.button.MaterialButton

class StockTransfersFragment : Fragment() {

    private val viewModel: StockTransfersViewModel by viewModels()
    private lateinit var adapter: StockTransferAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_stock_transfers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = StockTransferAdapter(
            onClick = { transfer -> openForm(transfer.id) },
            onDelete = { transfer -> confirmDelete(transfer) }
        )
        val rv = view.findViewById<RecyclerView>(R.id.rv_transfers)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        val emptyView = view.findViewById<TextView>(R.id.tv_no_transfers)

        view.findViewById<MaterialButton>(R.id.btn_new_transfer).setOnClickListener {
            openForm(-1L)
        }

        viewModel.transfers.observe(viewLifecycleOwner) { transfers ->
            adapter.submitList(transfers)
            emptyView.visibility = if (transfers.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (transfers.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun openForm(transferId: Long) {
        val bundle = Bundle().apply { putLong("transferId", transferId) }
        findNavController().navigate(R.id.stockTransferFormFragment, bundle)
    }

    private fun confirmDelete(transfer: StockTransfer) {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.btn_delete)
            .setPositiveButton(R.string.btn_delete) { _, _ -> viewModel.delete(transfer) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
