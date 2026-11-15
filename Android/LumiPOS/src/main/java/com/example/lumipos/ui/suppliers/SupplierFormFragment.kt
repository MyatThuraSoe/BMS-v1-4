package com.example.lumipos.ui.suppliers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Supplier
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class SupplierFormFragment : Fragment() {

    private val viewModel: SupplierFormViewModel by viewModels()
    private var editingSupplierId: Long = -1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_supplier_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editingSupplierId = arguments?.getLong("supplierId", -1) ?: -1

        if (editingSupplierId > 0) {
            viewModel.loadSupplier(editingSupplierId)
        }

        view.findViewById<MaterialButton>(R.id.btn_save_supplier).setOnClickListener {
            val name = view.findViewById<TextInputEditText>(R.id.et_name).text.toString().trim()
            val contactPerson = view.findViewById<TextInputEditText>(R.id.et_contact_person).text.toString().trim()
            val phone = view.findViewById<TextInputEditText>(R.id.et_phone).text.toString().trim()
            val email = view.findViewById<TextInputEditText>(R.id.et_email).text.toString().trim()
            val address = view.findViewById<TextInputEditText>(R.id.et_address).text.toString().trim()

            if (name.isEmpty() || phone.isEmpty()) {
                toast(getString(R.string.msg_supplier_required))
                return@setOnClickListener
            }

            viewModel.saveSupplier(
                Supplier(
                    id = editingSupplierId.coerceAtLeast(0),
                    name = name,
                    contactPerson = contactPerson,
                    phone = phone,
                    email = email,
                    address = address
                )
            )
        }

        viewModel.supplier.observe(viewLifecycleOwner) { supplier ->
            supplier?.let { s ->
                view.findViewById<TextInputEditText>(R.id.et_name).setText(s.name)
                view.findViewById<TextInputEditText>(R.id.et_contact_person).setText(s.contactPerson)
                view.findViewById<TextInputEditText>(R.id.et_phone).setText(s.phone)
                view.findViewById<TextInputEditText>(R.id.et_email).setText(s.email)
                view.findViewById<TextInputEditText>(R.id.et_address).setText(s.address)
            }
        }

        viewModel.saveSuccess.observe(viewLifecycleOwner) { success ->
            if (success == true) {
                toast(getString(R.string.msg_supplier_saved))
                findNavController().popBackStack()
            }
        }
    }
}