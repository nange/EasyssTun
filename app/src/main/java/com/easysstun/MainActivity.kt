package com.easysstun

import android.Manifest
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.easysstun.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var navController: NavController
    private lateinit var updateDialogs: UpdateDialogs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navHostFragment = NavHostFragment.create(R.navigation.nav_graph)
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, navHostFragment)
            .setPrimaryNavigationFragment(navHostFragment)
            .commitNow()

        navController = navHostFragment.navController
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        requestPermissionLauncher = registerForActivityResult(RequestPermission()) {
        }
        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        // In-app update: render the process-scoped updater state (progress survives
        // rotation) and run the throttled launch-time check.
        val updateManager = (application as EasyssTunApplication).updateManager
        updateDialogs = UpdateDialogs(this, updateManager)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateManager.state.collect { updateDialogs.render(it) }
            }
        }
        updateManager.autoCheck()
    }

    override fun onResume() {
        super.onResume()
        // Continues a pending install after the user returns from the
        // "install unknown apps" system settings screen.
        updateDialogs.onResume()
    }

    override fun onDestroy() {
        updateDialogs.dismiss()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}
