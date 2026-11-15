package com.example.lumipos.ui.stocktake

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
import com.example.lumipos.adapter.StockTakeAdapter
import com.example.lumipos.data.local.entity.StockTake
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton

class StockTakesFragment : Fragment() {

    private val viewModel: StockTakesViewModel by viewModels()
    private lateinit var adapter: StockTakeAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_stock_takes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = StockTakeAdapter(
            onClick = { take -> openForm(take.id) },
            onDelete = { take -> confirmDelete(take) }
        )
        val rv = view.findViewById<RecyclerView>(R.id.rv_stock_takes)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        val emptyView = view.findViewById<TextView>(R.id.tv_no_stock_takes)

        view.findViewById<MaterialButton>(R.id.btn_new_count).setOnClickListener {
            viewModel.startCount()
        }

        viewModel.takes.observe(viewLifecycleOwner) { takes ->
            adapter.submitList(takes)
            emptyView.visibility = if (takes.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (takes.isEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.startedId.observe(viewLifecycleOwner) { id ->
            if (id != null) {
                openForm(id)
                viewModel.consumedStartedId()
            }
        }
    }

    private fun openForm(takeId: Long) {
        val bundle = Bundle().apply { putLong("takeId", takeId) }
        findNavController().navigate(R.id.stockTakeFormFragment, bundle)
    }

    private fun confirmDelete(take: StockTake) {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.btn_delete)
            .setPositiveButton(R.string.btn_delete) { _, _ -> viewModel.delete(take) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
