package com.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.interfaz.AppContent
import com.example.interfaz.MainAppTheme
import com.example.interfaz.MainViewModel
import com.example.motor.parser.BaseEngine

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private fun setupCoilImageLoader() {
        try {
            val imageLoader = ImageLoader.Builder(applicationContext)
                .okHttpClient(BaseEngine.client)
                .memoryCache {
                    MemoryCache.Builder(applicationContext)
                        .maxSizePercent(0.15)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("image_cache"))
                        .maxSizeBytes(50 * 1024 * 1024)
                        .build()
                }
                .allowRgb565(true)
                .crossfade(true)
                .respectCacheHeaders(false)
                .build()
            Coil.setImageLoader(imageLoader)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting up Coil ImageLoader", e)
        }
    }

    private fun hideSystemUI() {
        try {
            val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } catch (e: Exception) {
            Log.e("MainActivity", "Error hiding system UI", e)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.motor.config.MemoryHelper.logMemoryDiagnostics(this, "MainActivity")
        setupCoilImageLoader()
        com.example.motor.repository.TmdbRepository.getInstance().init(cacheDir)
        com.example.motor.repository.SubtitleRepository.getInstance().init(cacheDir)
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            hideSystemUI()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in window setup", e)
        }
        enableEdgeToEdge()
        setContent {
            val activeThemeColors by viewModel.activeTheme.collectAsStateWithLifecycle()

            MainAppTheme(colors = activeThemeColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = parseHexColor(activeThemeColors.bg)
                ) {
                    AppContent(viewModel = viewModel, theme = activeThemeColors)
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            com.example.motor.repository.MediaCacheRepository.getInstance(application).trimMemory(level)
        } catch (_: Exception) {}
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            com.example.motor.repository.MediaCacheRepository.getInstance(application).clearMemoryCache(keepActiveTvOnly = true)
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        com.example.motor.player.PlayerRepository.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        com.example.motor.player.PlayerRepository.stop()
        com.example.motor.sync.LocalSyncServer.stop()
    }
}
