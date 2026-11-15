package com.example.lumipos.ui.receipt

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.ArrayRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.ReceiptCustomization
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.data.local.entity.SaleItem
import com.example.lumipos.data.local.entity.ShopInfo
import com.example.lumipos.ui.settings.SettingsViewModel
import com.example.lumipos.util.Extensions.toast
import com.example.lumipos.util.ReceiptGenerator
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

/**
 * Receipt customization.
 *
 * The preview is built by [ReceiptGenerator] - the very same function that produces the printed
 * receipt - rather than by a second, hand-rolled renderer. That matters: the previous preview
 * honoured only header/footer/divider/credit-info, so Show Logo, Show Address, Show Phone,
 * Show QR, Show Tax and Show Discount all persisted correctly but changed nothing on screen,
 * which made them look broken. Sharing one renderer makes that class of divergence impossible.
 */
class ReceiptCustomizationFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels()
    private var currentCustomization: ReceiptCustomization? = null
    private var currentShop: ShopInfo? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_receipt_customization, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSpinner(view, R.id.sp_paper_size, R.array.receipt_paper_sizes)
        setupSpinner(view, R.id.sp_font_size, R.array.receipt_font_sizes)
        setupSpinner(view, R.id.sp_header_align, R.array.receipt_header_aligns)
        setupSpinner(view, R.id.sp_divider_style, R.array.receipt_divider_styles)

        view.findViewById<MaterialButton>(R.id.btn_save_receipt_settings).setOnClickListener {
            save(view)
        }

        viewModel.receiptCustomization.observe(viewLifecycleOwner) { customization ->
            customization ?: return@observe
            currentCustomization = customization
            bind(view, customization)
        }

        // The shop details feed the preview header, so re-render whenever they arrive or change.
        viewModel.shopInfo.observe(viewLifecycleOwner) { shop ->
            currentShop = shop ?: ShopInfo()
            currentCustomization?.let { renderPreview(view, it) }
        }

        viewModel.saveSuccess.observe(viewLifecycleOwner) { success ->
            if (success == true) {
                toast(getString(R.string.msg_receipt_settings_saved))
                findNavController().popBackStack()
            }
        }
    }

    // ---------------------------------------------------------------- persistence

    private fun save(view: View) {
        val current = currentCustomization ?: ReceiptCustomization()
        val updated = current.copy(
            headerText = textOf(view, R.id.et_header_text),
            mainMessage = textOf(view, R.id.et_main_message),
            footerText = textOf(view, R.id.et_footer_text),
            paperSize = PAPER_SIZES.getOrElse(spinnerPosition(view, R.id.sp_paper_size)) { PAPER_SIZES[0] },
            fontSize = FONT_SIZES.getOrElse(spinnerPosition(view, R.id.sp_font_size)) { FONT_SIZES[1] },
            headerAlign = ALIGNS.getOrElse(spinnerPosition(view, R.id.sp_header_align)) { ALIGNS[1] },
            dividerStyle = DIVIDERS.getOrElse(spinnerPosition(view, R.id.sp_divider_style)) { DIVIDERS[0] },
            showLogo = switchOf(view, R.id.sw_show_logo),
            showShopName = switchOf(view, R.id.sw_show_shop_name),
            showAddress = switchOf(view, R.id.sw_show_address),
            showPhone = switchOf(view, R.id.sw_show_phone),
            showQRCode = switchOf(view, R.id.sw_show_qr),
            showCreditInfo = switchOf(view, R.id.sw_show_credit_info),
            showTax = switchOf(view, R.id.sw_show_tax),
            showDiscount = switchOf(view, R.id.sw_show_discount),
            boldShopName = switchOf(view, R.id.sw_bold_shop_name)
        )
        viewModel.updateReceiptCustomization(updated)
    }

    private fun bind(view: View, customization: ReceiptCustomization) {
        view.findViewById<TextInputEditText>(R.id.et_header_text).setText(customization.headerText)
        view.findViewById<TextInputEditText>(R.id.et_main_message).setText(customization.mainMessage)
        view.findViewById<TextInputEditText>(R.id.et_footer_text).setText(customization.footerText)

        view.findViewById<Spinner>(R.id.sp_paper_size).setSelection(
            PAPER_SIZES.indexOf(customization.paperSize).coerceAtLeast(0)
        )
        view.findViewById<Spinner>(R.id.sp_font_size).setSelection(
            FONT_SIZES.indexOf(customization.fontSize).coerceAtLeast(0)
        )
        view.findViewById<Spinner>(R.id.sp_header_align).setSelection(
            ALIGNS.indexOf(customization.headerAlign).coerceAtLeast(0)
        )
        view.findViewById<Spinner>(R.id.sp_divider_style).setSelection(
            DIVIDERS.indexOf(customization.dividerStyle).coerceAtLeast(0)
        )

        view.findViewById<SwitchMaterial>(R.id.sw_show_logo).isChecked = customization.showLogo
        view.findViewById<SwitchMaterial>(R.id.sw_show_shop_name).isChecked = customization.showShopName
        view.findViewById<SwitchMaterial>(R.id.sw_show_address).isChecked = customization.showAddress
        view.findViewById<SwitchMaterial>(R.id.sw_show_phone).isChecked = customization.showPhone
        view.findViewById<SwitchMaterial>(R.id.sw_show_qr).isChecked = customization.showQRCode
        view.findViewById<SwitchMaterial>(R.id.sw_show_credit_info).isChecked = customization.showCreditInfo
        view.findViewById<SwitchMaterial>(R.id.sw_show_tax).isChecked = customization.showTax
        view.findViewById<SwitchMaterial>(R.id.sw_show_discount).isChecked = customization.showDiscount
        view.findViewById<SwitchMaterial>(R.id.sw_bold_shop_name).isChecked = customization.boldShopName

        renderPreview(view, customization)
    }

    // ------------------------------------------------------------------- preview

    private fun renderPreview(view: View, custom: ReceiptCustomization) {
        val container = view.findViewById<LinearLayout>(R.id.receipt_preview)
        container.removeAllViews()

        val shop = currentShop ?: ShopInfo()

        if (custom.showLogo) {
            shop.logoBytes
                ?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                ?.let { bitmap ->
                    // logoSize is stored in dp; clamp so a bad value can't blow up the dialog.
                    val edge = (custom.logoSize.coerceIn(24, 160) * resources.displayMetrics.density)
                        .toInt().coerceAtLeast(1)
                    container.addView(ImageView(requireContext()).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = LinearLayout.LayoutParams(edge, edge).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            bottomMargin = dp(6)
                        }
                    })
                }
        }

        val receiptText = ReceiptGenerator.buildReceiptText(shop, custom, SAMPLE_SALE, SAMPLE_ITEMS)
        val lines = receiptText.trimEnd('\n').split('\n')

        // Alignment applies to the header block only - the body is column-aligned monospace, and
        // centring each of those lines individually would shear the columns apart.
        val dividerIndex = lines.indexOfFirst { line ->
            val t = line.trimStart()
            t.startsWith("-") && t.length >= 10
        }
        val headerLines = if (dividerIndex > 0) lines.subList(0, dividerIndex) else emptyList()
        val bodyLines = if (dividerIndex > 0) lines.subList(dividerIndex, lines.size) else lines

        val sizeSp = when (custom.fontSize) {
            "small" -> 10f
            "large" -> 15f
            else -> 12f
        }
        val headerGravity = when (custom.headerAlign) {
            "left" -> Gravity.START
            "right" -> Gravity.END
            else -> Gravity.CENTER
        }

        if (headerLines.isNotEmpty()) {
            container.addView(
                receiptLine(
                    text = headerLines.joinToString("\n"),
                    sizeSp = sizeSp,
                    gravity = headerGravity,
                    boldFirstLine = custom.boldShopName
                )
            )
        }
        if (bodyLines.isNotEmpty()) {
            container.addView(
                receiptLine(
                    text = bodyLines.joinToString("\n"),
                    sizeSp = sizeSp,
                    gravity = Gravity.START,
                    boldFirstLine = false
                )
            )
        }
    }

    private fun receiptLine(text: String, sizeSp: Float, gravity: Int, boldFirstLine: Boolean): TextView =
        TextView(requireContext()).apply {
            val breakIndex = text.indexOf('\n')
            if (boldFirstLine) {
                // Only the first line - that is the shop name; the address and phone follow it and
                // bolding those too would defeat the point of the switch.
                val end = if (breakIndex == -1) text.length else breakIndex
                this.text = SpannableString(text).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                this.text = text
            }
            setTextColor(Color.BLACK)
            textSize = sizeSp
            typeface = Typeface.MONOSPACE
            setLineSpacing(2f, 1f)
            this.gravity = gravity
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    // --------------------------------------------------------------------- utils

    private fun setupSpinner(view: View, id: Int, @ArrayRes options: Int) {
        val adapter = ArrayAdapter.createFromResource(
            requireContext(),
            options,
            android.R.layout.simple_spinner_item
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        view.findViewById<Spinner>(id).adapter = adapter
    }

    private fun textOf(view: View, id: Int): String =
        view.findViewById<TextInputEditText>(id).text?.toString()?.trim().orEmpty()

    private fun switchOf(view: View, id: Int): Boolean =
        view.findViewById<SwitchMaterial>(id).isChecked

    private fun spinnerPosition(view: View, id: Int): Int =
        view.findViewById<Spinner>(id).selectedItemPosition

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Stored codes, in the same order as the labels in res/values/arrays.xml. */
        val PAPER_SIZES = listOf("58", "80")
        val FONT_SIZES = listOf("small", "normal", "large")
        val ALIGNS = listOf("left", "center", "right")
        val DIVIDERS = listOf("dashed", "solid", "dotted")

        /**
         * Sample sale used only to render the preview. Discount, tax and change are all non-zero
         * on purpose: [ReceiptGenerator] omits those lines when the amount is zero, so a sample
         * without them would make the Show Discount / Show Tax switches look inert.
         */
        val SAMPLE_ITEMS = listOf(
            SaleItem(
                saleId = 0L, productId = 0L,
                productName = "Sample Product A", sku = "SKU-001",
                quantity = 2, unitPrice = 1500.0, totalPrice = 3000.0
            ),
            SaleItem(
                saleId = 0L, productId = 0L,
                productName = "Sample Product B", sku = "SKU-002",
                quantity = 1, unitPrice = 250.0, totalPrice = 250.0
            )
        )

        val SAMPLE_SALE = Sale(
            invoiceNumber = "INV-20260101120000-0001",
            customerName = "Walk-in Customer",
            cashierName = "Cashier",
            saleType = "CASH",
            paymentMethod = "CASH",
            paymentStatus = "PAID",
            subtotal = 3250.0,
            taxAmount = 157.5,
            discountAmount = 100.0,
            totalAmount = 3307.5,
            amountPaid = 3500.0
        )
    }
}
