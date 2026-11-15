package com.example.lumipos.ui.audit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.AuditLog

class AuditLogViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database

    val logs: LiveData<List<AuditLog>> = db.auditLogDao().getAll()
}
