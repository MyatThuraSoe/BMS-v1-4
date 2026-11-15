package com.example.lumipos.util

import android.content.Context

object BluetoothPrinter {

    private const val PREFS = "lumipos_hardware"
    private const val KEY_PRINTER = "printer_mac"

    private val escPosInit = byteArrayOf(0x1B, 0x40)
    private val cashDrawerKick = byteArrayOf(0x10, 0x14, 0x00, 0x00, 0x00)

    fun getAddress(context: Context): String? = prefs(context).getString(KEY_PRINTER, null)

    fun setAddress(context: Context, mac: String?) {
        val editor = prefs(context).edit()
        if (mac == null) editor.remove(KEY_PRINTER) else editor.putString(KEY_PRINTER, mac)
        editor.apply()
    }

    fun openCashDrawer(context: Context): Boolean {
        val mac = getAddress(context) ?: return false
        return send(mac, cashDrawerKick)
    }

    fun printEscPos(context: Context, payload: ByteArray): Boolean {
        val mac = getAddress(context) ?: return false
        return send(mac, escPosInit + payload)
    }

    private fun send(mac: String, bytes: ByteArray): Boolean {
        val connection = BluetoothSpp()
        return connection.use {
            it.connectMac(mac)
            it.write(bytes)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
