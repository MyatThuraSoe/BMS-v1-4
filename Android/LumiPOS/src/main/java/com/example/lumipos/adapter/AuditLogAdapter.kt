package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.AuditLog
import com.example.lumipos.util.DateFormatter

/** Read-only list of activity entries for the admin audit screen. */
class AuditLogAdapter(
    private var logs: List<AuditLog>
) : RecyclerView.Adapter<AuditLogAdapter.ViewHolder>() {

    fun submitList(newLogs: List<AuditLog>) {
        logs = newLogs
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val actionView: TextView = itemView.findViewById(R.id.tv_audit_action)
        val descriptionView: TextView = itemView.findViewById(R.id.tv_audit_description)
        val metaView: TextView = itemView.findViewById(R.id.tv_audit_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_audit_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val log = logs[position]
        holder.actionView.text = log.action
        holder.descriptionView.text = log.description
        holder.metaView.text = buildString {
            append(log.username.ifBlank { "system" })
            append(" · ")
            append(DateFormatter.formatDateTime(log.timestamp))
            if (log.entityType.isNotBlank()) {
                append(" · ")
                append(log.entityType)
                if (log.entityId > 0) append(" #${log.entityId}")
            }
        }
    }

    override fun getItemCount(): Int = logs.size
}
