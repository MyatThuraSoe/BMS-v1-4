package com.example.lumipos.ui.customers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Customer
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class CustomerFormFragment : Fragment() {

    private val viewModel: CustomerFormViewModel by viewModels()
    private var editingCustomerId: Long = -1
    private var loadedCustomer: Customer? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_customer_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editingCustomerId = arguments?.getLong("customerId", -1) ?: -1

        val tierSpinner = view.findViewById<android.widget.Spinner>(R.id.sp_customer_tier)
        tierSpinner.adapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            Customer.TIERS
        )

        if (editingCustomerId > 0) {
            viewModel.loadCustomer(editingCustomerId)
        }

        view.findViewById<MaterialButton>(R.id.btn_save_customer).setOnClickListener {
            val firstName = view.findViewById<TextInputEditText>(R.id.et_first_name).text.toString().trim()
            val lastName = view.findViewById<TextInputEditText>(R.id.et_last_name).text.toString().trim()
            val phone = view.findViewById<TextInputEditText>(R.id.et_phone).text.toString().trim()
            val email = view.findViewById<TextInputEditText>(R.id.et_email).text.toString().trim()
            val address = view.findViewById<TextInputEditText>(R.id.et_address).text.toString().trim()
            val city = view.findViewById<TextInputEditText>(R.id.et_city).text.toString().trim()
            val creditLimitStr = view.findViewById<TextInputEditText>(R.id.et_credit_limit).text.toString().trim()

            if (firstName.isEmpty() || lastName.isEmpty() || phone.isEmpty()) {
                toast(getString(R.string.msg_customer_required))
                return@setOnClickListener
            }

            val creditLimit = creditLimitStr.toDoubleOrNull()
            val tier = Customer.TIERS.getOrElse(
                view.findViewById<android.widget.Spinner>(R.id.sp_customer_tier).selectedItemPosition
            ) { Customer.TIER_RETAIL }

            val base = loadedCustomer
            val customer = if (base != null && base.id == editingCustomerId) {
                // Preserve fields the form does not edit (balance, loyalty wallet) so editing a
                // name never silently resets a customer's points or store credit to zero.
                base.copy(
                    firstName = firstName,
                    lastName = lastName,
                    phone = phone,
                    email = email,
                    address = address,
                    city = city,
                    creditLimit = creditLimit,
                    tier = tier
                )
            } else {
                Customer(
                    id = editingCustomerId.coerceAtLeast(0),
                    firstName = firstName,
                    lastName = lastName,
                    phone = phone,
                    email = email,
                    address = address,
                    city = city,
                    creditLimit = creditLimit,
                    tier = tier
                )
            }
            viewModel.saveCustomer(customer)
        }

        viewModel.customer.observe(viewLifecycleOwner) { customer ->
            customer?.let { c ->
                loadedCustomer = c
                view.findViewById<TextInputEditText>(R.id.et_first_name).setText(c.firstName)
                view.findViewById<TextInputEditText>(R.id.et_last_name).setText(c.lastName)
                view.findViewById<TextInputEditText>(R.id.et_phone).setText(c.phone)
                view.findViewById<TextInputEditText>(R.id.et_email).setText(c.email)
                view.findViewById<TextInputEditText>(R.id.et_address).setText(c.address)
                view.findViewById<TextInputEditText>(R.id.et_city).setText(c.city)
                c.creditLimit?.let { view.findViewById<TextInputEditText>(R.id.et_credit_limit).setText(it.toString()) }
                view.findViewById<android.widget.Spinner>(R.id.sp_customer_tier).setSelection(
                    Customer.TIERS.indexOf(c.tier).coerceAtLeast(0)
                )
                renderLoyalty(view, c)
            }
        }

        viewModel.saveSuccess.observe(viewLifecycleOwner) { success ->
            if (success == true) {
                toast(getString(R.string.msg_customer_saved))
                findNavController().popBackStack()
            }
        }
    }

    /** Shows the wallet only for a saved customer - a new record has nothing to show yet. */
    private fun renderLoyalty(view: View, customer: Customer) {
        val section = view.findViewById<android.view.View>(R.id.ll_loyalty)
        if (editingCustomerId <= 0) {
            section.visibility = android.view.View.GONE
            return
        }
        section.visibility = android.view.View.VISIBLE
        view.findViewById<android.widget.TextView>(R.id.tv_loyalty_points).text =
            getString(R.string.label_loyalty_points) + ": " +
                getString(R.string.format_loyalty_points, customer.loyaltyPoints)
        view.findViewById<android.widget.TextView>(R.id.tv_loyalty_credit).text =
            getString(R.string.label_store_credit) + ": " +
                com.example.lumipos.util.CurrencyFormatter.format(customer.storeCredit)

        view.findViewById<MaterialButton>(R.id.btn_add_credit).setOnClickListener {
            promptAmount(getString(R.string.btn_add_credit)) { amount ->
                if (amount > 0.0) viewModel.addStoreCredit(amount)
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_redeem_points).setOnClickListener {
            promptAmount(getString(R.string.btn_redeem_points)) { amount ->
                val points = amount.toInt()
                if (points > 0) viewModel.redeemPoints(points)
            }
        }
    }

    private fun promptAmount(title: String, onConfirm: (Double) -> Unit) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val value = input.text.toString().toDoubleOrNull() ?: 0.0
                onConfirm(value)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}