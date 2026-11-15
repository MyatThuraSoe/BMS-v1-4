package com.example.lumipos

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import com.example.lumipos.databinding.ActivityMainBinding
import com.example.lumipos.ui.settings.SettingsViewModel
import com.example.lumipos.util.CustomerDisplayController
import com.example.lumipos.util.KeyShortcutHandler
import com.example.lumipos.util.SecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var customerDisplay: CustomerDisplayController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        customerDisplay = CustomerDisplayController(this)

        installImePadding()

        // Resolve the NavHostFragment straight from the FragmentManager.
        //
        // findNavController(activity, id) works by looking the NavController up as a *view tag*:
        // it calls activity.findViewById(id) and then walks UP the view tree. That only succeeds
        // because NavHostFragment.onViewCreated() also tags the PARENT view, and only when the
        // host view is already attached to that parent at that moment. Looking the fragment up by
        // its id does not depend on any of that, so it is the more robust route. The standard
        // lookup stays as a fallback so behaviour is unchanged if the host is not there yet.
        navController = (supportFragmentManager
            .findFragmentById(R.id.fragment_container) as? NavHostFragment)
            ?.navController
            ?: findNavController(R.id.fragment_container)

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.dashboardFragment,
                R.id.posFragment,
                R.id.productsFragment,
                R.id.categoriesFragment,
                R.id.customersFragment,
                R.id.suppliersFragment,
                R.id.salesFragment,
                R.id.inventoryFragment,
                R.id.purchasesFragment,
                R.id.ordersFragment,
                R.id.reportsFragment,
                R.id.settingsFragment,
                R.id.cashShiftFragment,
                R.id.accountsReceivableFragment,
                R.id.shopInfoFragment,
                R.id.receiptCustomizationFragment
            ),
            binding.drawerLayout
        )

        // The activity uses Theme.LumiPOS.NoActionBar, so the MaterialToolbar in
        // app_bar_main.xml is NOT attached to the window decor. It must be wired
        // up manually here (this is also what makes `setSupportActionBar` safe).
        setSupportActionBar(binding.appBarMain.toolbar)

        // Drawer (hamburger) toggle + up arrow + title handled by NavigationUI.
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration)

        // Keep the drawer's checked item in sync with the current destination.
        NavigationUI.setupWithNavController(binding.navView, navController)

        applySecuritySettings()
    }

    /**
     * Applies the screenshot block (a setting, default on - a shop may disable it for training
     * captures) and warns once per launch when the device looks rooted.
     */
    private fun applySecuritySettings() {
        lifecycleScope.launch {
            val db = (application as LumiPOSApplication).database
            val blocked = withContext(Dispatchers.IO) {
                db.settingDao().getValue(SettingsViewModel.SECURITY_SCREENSHOT_KEY)?.toBoolean() ?: true
            }
            SecurityUtils.applyScreenshotBlock(window, blocked)

            val rooted = withContext(Dispatchers.IO) { SecurityUtils.isRooted(applicationContext) }
            if (rooted && !isFinishing) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.title_root_warning)
                    .setMessage(R.string.msg_root_warning)
                    .setPositiveButton(R.string.btn_close, null)
                    .show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return NavigationUI.navigateUp(navController, appBarConfiguration) ||
            super.onSupportNavigateUp()
    }

    override fun onStart() {
        super.onStart()
        customerDisplay.start()
    }

    override fun onStop() {
        customerDisplay.stop()
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (dispatchShortcut(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun dispatchShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val host = supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
        val current = host?.childFragmentManager?.primaryNavigationFragment
        if (current is KeyShortcutHandler) {
            return current.onKeyShortcut(keyCode, event)
        }
        return false
    }

    private fun installImePadding() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarMain.root) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                ime
            )
            insets
        }
    }
}