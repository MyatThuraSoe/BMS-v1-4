package com.example.lumipos.ui.shopinfo

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.ShopInfo
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.Extensions.toast
import com.example.lumipos.util.ImageUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class ShopInfoFragment : Fragment() {

    private val viewModel: ShopInfoViewModel by viewModels()
    private var logoBytes: ByteArray? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadLogo(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_shop_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.btn_choose_logo).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        view.findViewById<MaterialButton>(R.id.btn_remove_logo).setOnClickListener {
            logoBytes = null
            view.findViewById<ImageView>(R.id.iv_shop_logo).setImageResource(R.drawable.ic_shop)
        }

        view.findViewById<MaterialButton>(R.id.btn_save_shop_info).setOnClickListener {
            val shopName = view.findViewById<TextInputEditText>(R.id.et_shop_name).text.toString().trim()
            val address = view.findViewById<TextInputEditText>(R.id.et_address).text.toString().trim()
            val phone = view.findViewById<TextInputEditText>(R.id.et_phone).text.toString().trim()
            val email = view.findViewById<TextInputEditText>(R.id.et_email).text.toString().trim()
            val taxStr = view.findViewById<TextInputEditText>(R.id.et_tax).text.toString().trim()
            val currency = view.findViewById<TextInputEditText>(R.id.et_currency).text.toString().trim()

            if (shopName.isEmpty()) {
                toast(getString(R.string.msg_shop_name_required))
                return@setOnClickListener
            }

            val current = viewModel.shopInfo.value ?: ShopInfo()
            val updated = current.copy(
                shopName = shopName,
                address = address,
                phone = phone,
                email = email,
                taxPercentage = taxStr.toDoubleOrNull() ?: 0.0,
                currency = currency.ifBlank { "Ks" },
                logoBytes = logoBytes ?: current.logoBytes
            )
            viewModel.updateShopInfo(updated)
        }

        viewModel.shopInfo.observe(viewLifecycleOwner) { shopInfo ->
            shopInfo?.let { s ->
                view.findViewById<TextInputEditText>(R.id.et_shop_name).setText(s.shopName)
                view.findViewById<TextInputEditText>(R.id.et_address).setText(s.address)
                view.findViewById<TextInputEditText>(R.id.et_phone).setText(s.phone)
                view.findViewById<TextInputEditText>(R.id.et_email).setText(s.email)
                view.findViewById<TextInputEditText>(R.id.et_tax).setText(s.taxPercentage.toString())
                view.findViewById<TextInputEditText>(R.id.et_currency).setText(s.currency)
                s.logoBytes?.let { bytes ->
                    logoBytes = bytes
                    view.findViewById<ImageView>(R.id.iv_shop_logo).setImageBitmap(
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    )
                }
                CurrencyFormatter.setCurrency(s.currency)
            }
        }

        viewModel.saveSuccess.observe(viewLifecycleOwner) { success ->
            if (success == true) {
                toast(getString(R.string.msg_shop_info_saved))
                findNavController().popBackStack()
            }
        }
    }

    private fun loadLogo(uri: Uri) {
        val scaled = ImageUtils.loadScaledBitmap(requireContext(), uri, 256)
        if (scaled == null) {
            toast(getString(R.string.msg_failed_load_image))
            return
        }
        logoBytes = ImageUtils.toBytes(scaled, Bitmap.CompressFormat.PNG, 90)
        view?.findViewById<ImageView>(R.id.iv_shop_logo)?.setImageBitmap(scaled)
    }
}