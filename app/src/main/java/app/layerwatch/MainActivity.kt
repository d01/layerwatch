package app.layerwatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.layerwatch.ui.AppViewModel
import app.layerwatch.ui.Screen
import app.layerwatch.ui.SetupScreen
import app.layerwatch.ui.dashboard.DashboardScreen
import app.layerwatch.ui.theme.LayerwatchTheme
import app.layerwatch.ui.theme.Palette

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* screens retry on their own */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        requestPermissions()
        setContent {
            LayerwatchTheme {
                val screen by vm.screen.collectAsStateWithLifecycle()
                val setup by vm.setup.collectAsStateWithLifecycle()
                when (val s = screen) {
                    Screen.Loading -> Box(Modifier.fillMaxSize().background(Palette.ground))
                    is Screen.Setup -> {
                        val updating = s.prefillHost != null
                        if (updating) BackHandler(onBack = vm::cancelUpdate)
                        SetupScreen(setup, s.prefillHost, vm::connect, if (updating) vm::cancelUpdate else null)
                    }
                    is Screen.Dashboard -> DashboardScreen(
                        s.config, vm.tls,
                        onForget = vm::forgetPrinter,
                        onUpdateCode = vm::beginUpdateAccessCode,
                    )
                }
            }
        }
    }

    /**
     * Android 17 gates connections to private-range addresses behind a runtime permission;
     * notifications need one too.
     */
    private fun requestPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }
}
