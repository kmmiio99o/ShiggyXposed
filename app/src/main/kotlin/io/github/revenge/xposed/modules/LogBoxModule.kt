package io.github.revenge.xposed.modules

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Companion.reloadApp
import io.github.revenge.xposed.Utils.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object LogBoxModule : Module() {
    lateinit var packageParam: XC_LoadPackage.LoadPackageParam
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        this@LogBoxModule.packageParam = packageParam

        try {
            val dcdReactNativeHostClass = classLoader.loadClass("com.discord.bridge.DCDReactNativeHost")
            val getUseDeveloperSupportMethod = dcdReactNativeHostClass.methods.first { it.name == "getUseDeveloperSupport" }

            getUseDeveloperSupportMethod.hook {
                before {
                    result = true
                }
            }
        } catch (e: Exception) {
            Log.e("Failed to hook DCDReactNativeHost: ${e.message}")
        }


        listOf(
            "com.facebook.react.devsupport.BridgeDevSupportManager",
            "com.facebook.react.devsupport.BridgelessDevSupportManager"
        ).mapNotNull { packageParam.classLoader.safeLoadClass(it) }.forEach {
            hookDevSupportManager(it)
        }

        return@with
    }

    override fun onContext(context: Context) {
    }

    private fun hookDevSupportManager(clazz: Class<*>) {
        try {
            val handleReloadJSMethod = clazz.methods.first { it.name == "handleReloadJS" }
            handleReloadJSMethod.hook {
                before {
                    reloadApp()
                    result = null
                }
            }

            val showDevOptionsDialogMethod = clazz.methods.first { it.name == "showDevOptionsDialog" }
            showDevOptionsDialogMethod.hook {
                before {
                    try {
                        val context = getContextFromDevSupport(clazz, thisObject)
                        if (context != null) {
                            showRecoveryMenu(context)
                        }
                        result = null // Ignore the original dev menu
                    } catch (e: Exception) {
                        Log.e("Failed to show recovery menu: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Failed to hook DevSupportManager methods on class ${clazz.name}: ${e.message}")
        }
    }

    private fun getContextFromDevSupport(clazz: Class<*>, instance: Any?): Context? {
        return try {
            val mReactInstanceDevHelperField = XposedHelpers.findField(clazz, "mReactInstanceDevHelper")
            val helper = mReactInstanceDevHelperField.get(instance)
            val getCurrentActivityMethod = helper?.javaClass?.methods?.first { it.name == "getCurrentActivity" }
            getCurrentActivityMethod?.invoke(helper) as? Context
        } catch (e: Exception) {
            Log.e("Failed to get context: ${e.message}")
            null
        }
    }

    private fun showRecoveryMenu(context: Context) {
        val options = arrayOf(
            if (isSafeModeEnabled(context)) "Disable Safe Mode" else "Enable Safe Mode",
            "Reset Bundle",
            "Reload App"
        )

        AlertDialog.Builder(context)
            .setTitle("KettuXposed Recovery Menu")
            .setItems(options) { _, which ->
                handleMenuSelection(context, which)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleMenuSelection(context: Context, index: Int) {
        when (index) {
            0 -> toggleSafeMode(context)
            1 -> confirmAction(context, "reset bundle") { resetBundle(context) }
            2 -> reloadApp()
        }
    }

    private fun confirmAction(context: Context, actionText: String, action: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Confirm Action")
            .setMessage("Are you sure you want to $actionText?")
            .setPositiveButton("Confirm") { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isSafeModeEnabled(context: Context): Boolean {
        return try {
            val settingsFile = File(context.filesDir, "vd_mmkv/VENDETTA_SETTINGS")
            if (!settingsFile.exists()) return false

            val json = JSONObject(settingsFile.readText())
            json.optJSONObject("safeMode")?.optBoolean("enabled", false) ?: false
        } catch (e: Exception) {
            Log.e("Error checking safe mode: ${e.message}")
            false
        }
    }

    private fun toggleSafeMode(context: Context) {
        try {
            val settingsFile = File(context.filesDir, "vd_mmkv/VENDETTA_SETTINGS")
            val themeFile = File(context.filesDir, "vd_mmkv/VENDETTA_THEMES")

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

    private fun resetBundle(context: Context) {
        try {
            val pyoncordDir = getPyoncordDirectory(context)
            val bundleFile = File(pyoncordDir, "bundle.js")
            val backupFile = File(pyoncordDir, "bundle.js.backup")
            val configFile = File(pyoncordDir, "loader_config.json")

            bundleFile.delete()
            backupFile.delete()

            if (configFile.exists()) {
                val config = JSONObject(configFile.readText())
                config.put("customLoadUrlEnabled", false)
                configFile.writeText(config.toString())
            }

            reloadApp()
        } catch (e: Exception) {
            Log.e("Error resetting bundle: ${e.message}")
            showError(context, "Failed to reset bundle", e.message)
        }
    }

    private fun getPyoncordDirectory(context: Context): File {
        val dir = File(context.filesDir, "pyoncord")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun showError(context: Context, title: String, message: String?) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message ?: "An unknown error occurred")
            .setPositiveButton("OK", null)
            .show()
    }
}