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
import com.example.lumipos.adapter.StockTakeLineAdapter
import com.example.lumipos.data.local.entity.StockTakeItem
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton

class StockTakeFormFragment : Fragment() {

    private val viewModel: StockTakeFormViewModel by viewModels()
    private lateinit var adapter: StockTakeLineAdapter
    private val lines = mutableListOf<StockTakeItem>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_stock_take_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val takeId = arguments?.getLong("takeId", -1) ?: -1
        if (takeId <= 0) {
            findNavController().popBackStack()
            return
        }
        viewModel.load(takeId)

        adapter = StockTakeLineAdapter(lines, blind = true) { itemId, counted ->
            viewModel.setCounted(itemId, counted)
        }
        val rv = view.findViewById<RecyclerView>(R.id.rv_count_lines)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        val btnComplete = view.findViewById<MaterialButton>(R.id.btn_complete_count)

        viewModel.take.observe(viewLifecycleOwner) { take ->
            take ?: return@observe
            view.findViewById<TextView>(R.id.tv_take_header).text = take.countNumber
            val inProgress = take.status == "IN_PROGRESS"
            adapter.setBlind(inProgress)
            btnComplete.isEnabled = inProgress
            view.findViewById<TextView>(R.id.tv_take_status).text =
                getString(if (inProgress) R.string.status_in_progress else R.string.status_completed)
        }

        viewModel.items.observe(viewLifecycleOwner) { items ->
            lines.clear()
            lines.addAll(items)
            adapter.notifyDataSetChanged()
        }

        btnComplete.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.msg_confirm_complete_count)
                .setPositiveButton(R.string.btn_complete_count) { _, _ -> viewModel.complete() }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }

        viewModel.completed.observe(viewLifecycleOwner) { done ->
            if (done == true) {
                toast(getString(R.string.msg_stock_take_completed))
                findNavController().popBackStack()
            }
        }
    }
}
