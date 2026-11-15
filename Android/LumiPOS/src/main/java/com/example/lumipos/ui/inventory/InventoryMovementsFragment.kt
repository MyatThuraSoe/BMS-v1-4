package com.example.lumipos.ui.inventory

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.StockMovementAdapter
import com.google.android.material.button.MaterialButton

class InventoryMovementsFragment : Fragment() {

    private val viewModel: InventoryViewModel by viewModels()
    private lateinit var movementAdapter: StockMovementAdapter

    /** null == "All types". */
    private var selectedType: String? = null

    /** Index 0 is the "all" entry; the rest map to the movementType values the app writes. */
    private val typeCodes = listOf(null, "IN", "OUT", "ADJUSTMENT_IN", "ADJUSTMENT_OUT")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_inventory_movements, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        movementAdapter = StockMovementAdapter(emptyList())
        val recycler = view.findViewById<RecyclerView>(R.id.rv_movements)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = movementAdapter

        val emptyState = view.findViewById<View>(R.id.empty_state)
        val search = view.findViewById<EditText>(R.id.et_movement_search)
        val filterButton = view.findViewById<MaterialButton>(R.id.btn_movement_filter)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setMovementQuery(s?.toString().orEmpty())
            }
        })

        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                v.clearFocus()
                val imm = requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else {
                false
            }
        }

        filterButton.setOnClickListener { showTypeFilterDialog() }

        viewModel.filteredMovements.observe(viewLifecycleOwner) { movements ->
            movementAdapter.submitList(movements)
            emptyState.visibility = if (movements.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showTypeFilterDialog() {
        val labels = typeCodes.map { typeLabel(it) }.toTypedArray()
        val checked = typeCodes.indexOf(selectedType).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.filter_movement_type)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                selectedType = typeCodes[which]
                viewModel.setMovementTypeFilter(selectedType)
                view?.findViewById<MaterialButton>(R.id.btn_movement_filter)?.text = typeLabel(selectedType)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun typeLabel(type: String?): String = when (type) {
        "IN" -> getString(R.string.movement_type_in)
        "OUT" -> getString(R.string.movement_type_out)
        "ADJUSTMENT_IN" -> getString(R.string.movement_type_adjustment_in)
        "ADJUSTMENT_OUT" -> getString(R.string.movement_type_adjustment_out)
        else -> getString(R.string.filter_all_types)
    }
}
