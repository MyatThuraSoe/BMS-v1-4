package com.example.lumipos.ui.receipt

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.ReceiptCustomization
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.data.local.entity.ShopInfo
import com.example.lumipos.data.repository.SaleRepository
import com.example.lumipos.data.repository.ShopInfoRepository
import com.example.lumipos.util.Extensions.toast
import com.example.lumipos.util.ReceiptGenerator
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

class ReceiptPreviewFragment : Fragment() {

    private var saleId: Long = -1L
    private var receiptText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saleId = arguments?.getLong("saleId", -1L) ?: -1L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_receipt_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.btn_print_receipt).setOnClickListener {
            printReceipt(receiptText)
        }

        view.findViewById<MaterialButton>(R.id.btn_copy_receipt).setOnClickListener {
            shareReceipt(receiptText)
        }

        loadAndRender(view)
    }

    private fun loadAndRender(view: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val app = requireContext().applicationContext as LumiPOSApplication
                    val db = app.database
                    val saleRepo = SaleRepository(db.saleDao(), db.saleItemDao())
                    val shopRepo = ShopInfoRepository(db.shopInfoDao(), db.receiptCustomizationDao(), db.settingDao())

                    val sale = saleRepo.getById(saleId)
                    val items = if (sale != null) saleRepo.getItemsBySaleId(saleId) else emptyList()
                    val shop = shopRepo.getShopInfoSync() ?: ShopInfo()
                    val custom = shopRepo.getReceiptCustomizationSync() ?: ReceiptCustomization()
                    Quad(sale, shop, custom, items)
                }

                val sale = result.a
                if (sale == null) {
                    toast(getString(R.string.msg_sale_not_found))
                    return@launch
                }
                receiptText = ReceiptGenerator.buildReceiptText(result.b, result.c, sale, result.d)
                val container = view.findViewById<LinearLayout>(R.id.receipt_content)
                container.removeAllViews()
                container.addView(TextView(requireContext()).apply {
                    text = receiptText
                    setTextColor(Color.BLACK)
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                    setLineSpacing(2f, 1f)
                })
                if (result.c.showQRCode) {
                    val qr = generateQr("LumiPOS-${sale.invoiceNumber}")
                    qr?.let {
                        val imageView = ImageView(requireContext())
                        imageView.setImageBitmap(it)
                        imageView.layoutParams = LinearLayout.LayoutParams(dp(120), dp(120))
                        container.addView(imageView)
                    }
                }
            } catch (e: Exception) {
                toast(getString(R.string.msg_failed_load_receipt, e.message ?: ""))
            }
        }
    }

    private fun generateQr(content: String): Bitmap? {
        return try {
            val size = 256
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
        } catch (e: Exception) {
            null
        }
    }

    private fun shareReceipt(text: String) {
        if (text.isEmpty()) return
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(sendIntent, "Export Receipt"))
    }

    private fun printReceipt(text: String) {
        if (text.isEmpty()) {
            toast(getString(R.string.msg_nothing_to_print))
            return
        }
        val printManager = requireContext().getSystemService(Context.PRINT_SERVICE) as PrintManager
        printManager.print(
            "LumiPOS Receipt",
            object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes?,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback?,
                    extras: Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback?.onLayoutCancelled()
                        return
                    }
                    callback?.onLayoutFinished(
                        PrintDocumentInfo.Builder("receipt")
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                            .build(),
                        true
                    )
                }

                override fun onWrite(
                    pages: Array<out PageRange>?,
                    destination: ParcelFileDescriptor?,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback?
                ) {
                    try {
                        val pdf = PdfDocument()
                        val paint = android.graphics.Paint().apply {
                            color = Color.BLACK
                            textSize = 10f
                            typeface = Typeface.MONOSPACE
                        }
                        val pageWidth = 300
                        val pageHeight = 800
                        val lines = text.split("\n").flatMap { wrap(it, 34) }

                        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
                        var canvas = page.canvas
                        var y = 20f

                        for (line in lines) {
                            if (y > pageHeight - 60) {
                                pdf.finishPage(page)
                                page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
                                canvas = page.canvas
                                y = 20f
                            }
                            canvas.drawText(line, 20f, y, paint)
                            y += 16f
                        }
                        pdf.finishPage(page)

                        destination?.let { fd ->
                            FileOutputStream(fd.fileDescriptor).use { out ->
                                pdf.writeTo(out)
                            }
                        }
                        pdf.close()
                        callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback?.onWriteFailed(e.message)
                    }
                }
            },
            null
        )
    }

    private fun wrap(text: String, maxLen: Int): List<String> {
        val lines = mutableListOf<String>()
        var current = text
        while (current.length > maxLen) {
            var cut = maxLen
            while (cut > 0 && current[cut] != ' ') cut--
            if (cut == 0) cut = maxLen
            lines.add(current.substring(0, cut).trimEnd())
            current = current.substring(cut).trimStart()
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}