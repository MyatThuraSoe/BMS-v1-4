package com.example.lumipos.util

import android.content.Context

object WeighingScale {

    private const val PREFS = "lumipos_hardware"
    private const val KEY_SCALE = "scale_mac"

    private val weightRegex = Regex("([-+]?\\d+(?:\\.\\d+)?)")

    @Volatile
    private var connection: BluetoothSpp? = null

    fun getAddress(context: Context): String? = prefs(context).getString(KEY_SCALE, null)

    fun setAddress(context: Context, mac: String?) {
        val editor = prefs(context).edit()
        if (mac == null) editor.remove(KEY_SCALE) else editor.putString(KEY_SCALE, mac)
        editor.apply()
    }

    fun start(context: Context, onWeightKg: (Double) -> Unit, onError: (String) -> Unit) {
        val mac = getAddress(context) ?: run {
            onError("No scale configured")
            return
        }
        stop()
        val spp = BluetoothSpp()
        val connected = try {
            spp.connectMac(mac)
        } catch (e: Exception) {
            false
        }
        if (!connected) {
            onError("Could not connect to scale")
            return
        }
        connection = spp
        spp.startReading { line -> parseWeightKg(line)?.let(onWeightKg) }
    }

    fun stop() {
        connection?.close()
        connection = null
    }

    fun parseWeightKg(line: String): Double? {
        val cleaned = line.trim()
        if (cleaned.isEmpty()) return null
        val match = weightRegex.find(cleaned) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val lower = cleaned.lowercase()
        return when {
            "kg" in lower -> value
            "lb" in lower -> value * 0.45359237
            else -> value / 1000.0
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
