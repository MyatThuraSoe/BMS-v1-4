package com.example.lumipos.util

/**
 * Role/permission matrix for the shared POS terminal.
 *
 * The app has no per-user login - a terminal is normally used by whichever staff member is on
 * shift. Instead a low-privilege role is active by default and sensitive actions are protected by
 * a one-shot manager/admin PIN (see [PermissionGate]). The active role and the PINs live in the
 * settings table so no schema change is needed.
 */
object Permissions {

    const val KEY_ACTIVE_ROLE = "security_active_role"
    const val KEY_MANAGER_PIN = "security_manager_pin"
    const val KEY_ADMIN_PIN = "security_admin_pin"
    const val DEFAULT_MANAGER_PIN = "1234"
    const val DEFAULT_ADMIN_PIN = "9999"

    enum class Role { CASHIER, MANAGER, ADMIN }

    enum class Permission {
        /** Void a completed sale. */
        VOID_SALE,
        /** Apply a cart-level (bill) discount. */
        BILL_DISCOUNT,
        /** Delete a product from the catalogue. */
        DELETE_PRODUCT,
        /** Export the database / reports off the device. */
        EXPORT_DATA,
        /** Edit store settings, loyalty rates and security options. */
        EDIT_SETTINGS,
        /** View the reports screen. */
        VIEW_REPORTS,
        /** Read the activity audit log. */
        VIEW_AUDIT_LOGS
    }

    fun roleFrom(value: String?): Role =
        Role.values().firstOrNull { it.name == value } ?: Role.CASHIER

    /** Cashiers sell; managers add void/discount/report powers; admins do everything. */
    fun permissionsFor(role: Role): Set<Permission> = when (role) {
        Role.CASHIER -> emptySet()
        Role.MANAGER -> setOf(
            Permission.VOID_SALE,
            Permission.BILL_DISCOUNT,
            Permission.VIEW_REPORTS,
            Permission.EXPORT_DATA
        )
        Role.ADMIN -> Permission.values().toSet()
    }

    /** PIN required to act as [role]; cashier needs none. */
    fun pinKeyFor(role: Role): String? = when (role) {
        Role.CASHIER -> null
        Role.MANAGER -> KEY_MANAGER_PIN
        Role.ADMIN -> KEY_ADMIN_PIN
    }

    fun defaultPinFor(role: Role): String? = when (role) {
        Role.CASHIER -> null
        Role.MANAGER -> DEFAULT_MANAGER_PIN
        Role.ADMIN -> DEFAULT_ADMIN_PIN
    }
}
