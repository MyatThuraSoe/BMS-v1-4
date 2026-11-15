package com.example.lumipos.util

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.example.lumipos.R

object CustomerDisplayBus {

    @Volatile
    var total: Double = 0.0
        set(value) {
            field = value
            listener?.invoke(value, shopName)
        }

    @Volatile
    var shopName: String = ""
        set(value) {
            field = value
            listener?.invoke(total, value)
        }

    @Volatile
    private var listener: ((Double, String) -> Unit)? = null

    fun register(callback: (Double, String) -> Unit) {
        listener = callback
        callback(total, shopName)
    }

    fun unregister() {
        listener = null
    }
}

class CustomerDisplayController(private val context: Context) {

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var presentation: CustomerPresentation? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    fun start() {
        displayManager.registerDisplayListener(displayListener, null)
        refresh()
    }

    fun stop() {
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        presentation?.dismiss()
        presentation = null
    }

    private fun refresh() {
        val secondary = displayManager.displays
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        val current = presentation
        if (secondary == null) {
            current?.dismiss()
            presentation = null
            return
        }
        if (current != null && current.display.displayId == secondary.displayId) return
        current?.dismiss()
        val created = CustomerPresentation(context, secondary)
        runCatching { created.show() }
        presentation = created
    }
}

class CustomerPresentation(outerContext: Context, display: Display) :
    Presentation(outerContext, display) {

    private lateinit var tvShop: TextView
    private lateinit var tvTotal: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view: View =
            LayoutInflater.from(context).inflate(R.layout.presentation_customer_display, null)
        setContentView(view)
        tvShop = view.findViewById(R.id.tv_cfd_shop)
        tvTotal = view.findViewById(R.id.tv_cfd_total)
    }

    override fun onStart() {
        super.onStart()
        CustomerDisplayBus.register { total, shop ->
            tvShop.text = shop
            tvTotal.text = CurrencyFormatter.format(total)
        }
    }

    override fun onStop() {
        CustomerDisplayBus.unregister()
        super.onStop()
    }
}
