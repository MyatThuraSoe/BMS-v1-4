package com.example.lumipos

import android.util.Log
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: navigate to every destination in the nav graph and let each fragment actually
 * inflate, bind its view model and touch the database.
 *
 * This exists because a launch crash hides every screen behind it -- until MainActivity survives
 * onCreate, no other fragment has ever run. Instrumentation runs in-process, so it works even on
 * devices that block `adb shell input` (MIUI rejects it with INJECT_EVENTS).
 *
 * Progress is logged as "SMOKE -> <name>" before each hop, so if the process dies the last line in
 * logcat identifies the offending destination.
 */
@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {

    private val destinations = listOf(
        "dashboardFragment" to R.id.dashboardFragment,
        "posFragment" to R.id.posFragment,
        "productsFragment" to R.id.productsFragment,
        "productFormFragment" to R.id.productFormFragment,
        "categoriesFragment" to R.id.categoriesFragment,
        "categoryFormFragment" to R.id.categoryFormFragment,
        "customersFragment" to R.id.customersFragment,
        "customerFormFragment" to R.id.customerFormFragment,
        "suppliersFragment" to R.id.suppliersFragment,
        "supplierFormFragment" to R.id.supplierFormFragment,
        "salesFragment" to R.id.salesFragment,
        "saleDetailFragment" to R.id.saleDetailFragment,
        "cashShiftFragment" to R.id.cashShiftFragment,
        "ordersFragment" to R.id.ordersFragment,
        "accountsReceivableFragment" to R.id.accountsReceivableFragment,
        "inventoryFragment" to R.id.inventoryFragment,
        "stockAdjustmentFragment" to R.id.stockAdjustmentFragment,
        "purchasesFragment" to R.id.purchasesFragment,
        "purchaseFormFragment" to R.id.purchaseFormFragment,
        "reportsFragment" to R.id.reportsFragment,
        "settingsFragment" to R.id.settingsFragment,
        "shopInfoFragment" to R.id.shopInfoFragment,
        "receiptCustomizationFragment" to R.id.receiptCustomizationFragment,
        "receiptPreviewFragment" to R.id.receiptPreviewFragment
    )

    @Test
    fun everyDestinationInflates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((name, id) in destinations) {
                Log.i(TAG, "SMOKE -> $name")
                scenario.onActivity { activity ->
                    val navHost = activity.supportFragmentManager
                        .findFragmentById(R.id.fragment_container) as NavHostFragment
                    navHost.navController.navigate(id)
                }
                // Let the fragment transaction run and observers fire.
                instrumentation.waitForIdleSync()
                Thread.sleep(400)
                instrumentation.waitForIdleSync()
            }
        }
        Log.i(TAG, "SMOKE -> all destinations reached")
    }

    private companion object {
        const val TAG = "NavigationSmokeTest"
    }
}
