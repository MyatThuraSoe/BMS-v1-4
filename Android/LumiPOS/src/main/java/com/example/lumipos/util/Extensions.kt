package com.example.lumipos.util

import android.content.Context
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

object Extensions {
    fun Context.toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    fun Fragment.toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    fun Context.longToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    fun Fragment.longToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    /**
     * Tints a status chip for [status]. Pair with `@drawable/bg_status_chip`, which is a plain
     * rounded shape precisely so it can be tinted like this - see [StatusColors].
     */
    fun TextView.applyStatusTint(status: String) {
        backgroundTintList = ContextCompat.getColorStateList(context, StatusColors.of(status))
    }
}
