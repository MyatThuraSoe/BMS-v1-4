package com.example.lumipos.util

import android.view.KeyEvent

interface KeyShortcutHandler {
    fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean
}
