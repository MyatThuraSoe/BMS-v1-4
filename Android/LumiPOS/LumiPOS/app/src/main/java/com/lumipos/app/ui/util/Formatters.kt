package com.lumipos.app.ui.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Double.asMoney(): String = "%,.2f".format(Locale.getDefault(), this)

fun Double.asCurrency(): String = "$" + asMoney()

fun Long.asDateTime(): String =
    SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(this))

fun Long.asDate(): String =
    SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(this))