package ShiggyXposed.xposed.modules.LogBox

import android.content.Context
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import ShiggyXposed.xposed.Utils.Log
import ShiggyXposed.xposed.Utils.Companion.reloadApp
import ShiggyXposed.xposed.Constants
import kotlinx.coroutines.*

object LogBoxActions {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun toggleSafeMode(context: Context) {
        try {
            val settingsFile = File(context.filesDir, "logbox/LOGBOX_SETTINGS")
            val themeFile = File(context.filesDir, "logbox/LOGBOX_THEMES")

            settingsFile.parentFile?.mkdirs()

            val settings = if (settingsFile.exists()) {
                JSONObject(settingsFile.readText())
            } else {
                JSONObject()
            }

            val safeMode = settings.optJSONObject("safeMode") ?: JSONObject()
            val currentState = safeMode.optBoolean("enabled", false)
            val newState = !currentState

            safeMode.put("enabled", newState)

            if (newState && themeFile.exists()) {
                val theme = JSONObject(themeFile.readText())
                val themeId = theme.optString("id")
                if (themeId.isNotEmpty()) {
                    safeMode.put("currentThemeId", themeId)
                    themeFile.delete()
                }
            }

            settings.put("safeMode", safeMode)
            settingsFile.writeText(settings.toString())

            Toast.makeText(context, "Safe Mode ${if (newState) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
            reloadApp()

        } catch (e: Exception) {
            Log.e("Error toggling safe mode: ${e.message}")
            showError(context, "Failed to toggle safe mode", e.message)
        }
    }

    fun toggleBundleInjection(context: Context) {
        try {
            val cacheDir = File(context.dataDir, Constants.CACHE_DIR)
            cacheDir.mkdirs()
            val bundle = File(cacheDir, Constants.MAIN_SCRIPT_FILE)
            val disabled = File(cacheDir, "${Constants.MAIN_SCRIPT_FILE}.disabled")

            val settingsFile = File(context.filesDir, "logbox/LOGBOX_SETTINGS")
            settingsFile.parentFile?.mkdirs()
            val settings = if (settingsFile.exists()) JSONObject(settingsFile.readText()) else JSONObject()

            val currentlyDisabled = LogBoxUtils.isBundleInjectionDisabled(context)
            val newState = !currentlyDisabled

            if (bundle.exists() && disabled.exists()) {
                bundle.delete()
            }

            if (newState) {
                // Disable injection
                if (disabled.exists()) {
                    disabled.delete()
                }
                if (bundle.exists()) {
                    bundle.renameTo(disabled)
                } else {
                    disabled.writeText("")
                }
                settings.put("bundleInjectionDisabled", true)
                Toast.makeText(context, "Bundle injection disabled", Toast.LENGTH_SHORT).show()
            } else {
                // Enable injection
                if (disabled.exists()) {
                    disabled.renameTo(bundle)
                } else if (!bundle.exists()) {
                    bundle.writeText("")
                }
                settings.put("bundleInjectionDisabled", false)
                Toast.makeText(context, "Bundle injection enabled", Toast.LENGTH_SHORT).show()
            }

            settingsFile.writeText(settings.toString())

        } catch (e: Exception) {
            Log.e("Error toggling bundle injection: ${e.message}")
            showError(context, "Failed to toggle bundle injection", e.message)
        }
    }

    fun refetchBundle(context: Context) {
        try {
            val pyoncordDir = LogBoxUtils.getPyoncordDirectory(context)
            val bundleFile = File(pyoncordDir, "bundle.js")
            val backupFile = File(pyoncordDir, "bundle.js.backup")

            if (bundleFile.exists()) {
                backupFile.delete()
                bundleFile.renameTo(backupFile)
                Log.e("Bundle moved to backup")
            }

            reloadApp()

        } catch (e: Exception) {
            Log.e("Error refetching bundle: ${e.message}")
            showError(context, "Failed to refetch bundle", e.message)
        }
    }

    fun clearCacheAndReset(context: Context) {
        scope.launch(Dispatchers.IO) {
            try {
                val pyoncordDir = LogBoxUtils.getPyoncordDirectory(context)
                val bundleFile = File(pyoncordDir, "bundle.js")
                val configFile = File(pyoncordDir, "loader.json")

                bundleFile.delete()

                if (configFile.exists()) {
                    val config = JSONObject(configFile.readText())
                    val customLoadUrl = config.optJSONObject("customLoadUrl") ?: JSONObject()
                    customLoadUrl.put("enabled", false)
                    customLoadUrl.put("url", "http://localhost:4040/shiggycord.js")
                    config.put("customLoadUrl", customLoadUrl)
                    configFile.writeText(config.toString())
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Cache cleared successfully", Toast.LENGTH_SHORT).show()
                    reloadApp()
                }

            } catch (e: Exception) {
                Log.e("Error clearing cache: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to clear cache", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun setCustomBundleURL(context: Context, url: String, enabled: Boolean) {
        try {
            val pyoncordDir = LogBoxUtils.getPyoncordDirectory(context)
            val configFile = File(pyoncordDir, "loader.json")

            val config = if (configFile.exists()) {
                JSONObject(configFile.readText())
            } else {
                JSONObject().apply {
                    put("loadReactDevTools", false)
                }
            }

            val customLoadUrl = config.optJSONObject("customLoadUrl") ?: JSONObject()
            customLoadUrl.put("enabled", enabled)
            customLoadUrl.put("url", url)
            config.put("customLoadUrl", customLoadUrl)

            configFile.writeText(config.toString())

            if (enabled) {
                File(pyoncordDir, "bundle.js").delete()
                Toast.makeText(context, "Custom bundle enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Custom bundle disabled", Toast.LENGTH_SHORT).show()
            }

            reloadApp()
        } catch (e: Exception) {
            Log.e("Error setting custom bundle URL: ${e.message}")
            showError(context, "Failed to save configuration", e.message)
        }
    }

    fun showError(context: Context, title: String, message: String?) {
        LogBoxMenu.showErrorDialog(context, title, message)
    }
}
