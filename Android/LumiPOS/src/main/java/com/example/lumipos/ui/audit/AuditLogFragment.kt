package com.example.lumipos.ui.audit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.AuditLogAdapter
import com.example.lumipos.util.PermissionGate
import com.example.lumipos.util.Permissions

/**
 * Admin-only activity log (RBAC: "Admin: can view audit logs"). The list itself is loaded only
 * after [PermissionGate] passes, so a cashier who somehow reaches this screen sees a locked
 * placeholder rather than the data.
 */
class AuditLogFragment : Fragment() {

    private val viewModel: AuditLogViewModel by viewModels()
    private lateinit var adapter: AuditLogAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_audit_log, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val locked = view.findViewById<TextView>(R.id.tv_audit_locked)
        val empty = view.findViewById<TextView>(R.id.tv_audit_empty)

        adapter = AuditLogAdapter(emptyList())
        view.findViewById<RecyclerView>(R.id.rv_audit_logs).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AuditLogFragment.adapter
        }

        PermissionGate.require(this, Permissions.Permission.VIEW_AUDIT_LOGS) {
            locked.visibility = View.GONE
            viewModel.logs.observe(viewLifecycleOwner) { logs ->
                adapter.submitList(logs)
                empty.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
