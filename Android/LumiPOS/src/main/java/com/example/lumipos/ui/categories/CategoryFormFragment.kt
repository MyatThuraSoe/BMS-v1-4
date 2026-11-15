package com.example.lumipos.ui.categories

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Category
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class CategoryFormFragment : Fragment() {

    private val viewModel: CategoryFormViewModel by viewModels()
    private var editingCategoryId: Long = -1L

    /**
     * The row being edited, once loaded. Held so the save can `copy()` it - which is what keeps
     * `id` and `createdAt` intact. The previous version always built a fresh `Category`, so the
     * autoGenerate id was 0 (inserting a duplicate on edit) and `createdAt` reset to now.
     */
    private var loadedCategory: Category? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_category_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editingCategoryId = arguments?.getLong("categoryId", -1L) ?: -1L

        // Nothing used to load the row being edited, so "Edit" opened a blank form.
        if (editingCategoryId > 0) {
            viewModel.loadCategory(editingCategoryId)
        }

        val nameLayout = view.findViewById<TextInputLayout>(R.id.til_category_name)

        view.findViewById<MaterialButton>(R.id.btn_save_category).setOnClickListener {
            nameLayout.error = null

            val name = view.findViewById<TextInputEditText>(R.id.et_category_name).text.toString().trim()
            val description = view.findViewById<TextInputEditText>(R.id.et_category_desc).text.toString().trim()

            if (name.isBlank()) {
                // Inline error on the field itself, rather than a toast that vanishes.
                nameLayout.error = getString(R.string.msg_category_name_required)
                return@setOnClickListener
            }

            // Falls back to a Category carrying editingCategoryId so that saving before the load
            // completes still updates the right row instead of inserting a second one.
            val base = loadedCategory
                ?: Category(id = editingCategoryId.coerceAtLeast(0L), name = name)

            viewModel.saveCategory(base.copy(name = name, description = description))
        }

        viewModel.category.observe(viewLifecycleOwner) { category ->
            category ?: return@observe
            loadedCategory = category
            view.findViewById<TextInputEditText>(R.id.et_category_name).setText(category.name)
            view.findViewById<TextInputEditText>(R.id.et_category_desc).setText(category.description)
        }

        viewModel.saveSuccess.observe(viewLifecycleOwner) { success ->
            if (success == true) {
                toast(getString(R.string.msg_category_saved))
                findNavController().popBackStack()
            }
        }
    }
}
