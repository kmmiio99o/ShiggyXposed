package io.github.revenge.xposed.modules

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
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
    private var contextForMenu: Context? = null

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
            Log.e("Successfully hooked DCDReactNativeHost")
        } catch (e: Exception) {
            Log.e("Failed to hook DCDReactNativeHost: ${e.message}")
        }

        return@with
    }

    override fun onContext(context: Context) {
        try {
            Log.e("onContext called with context: $context")
            contextForMenu = context
            
            val possibleClasses = listOf(
                "com.facebook.react.devsupport.BridgeDevSupportManager",
                "com.facebook.react.devsupport.BridgelessDevSupportManager",
                "com.facebook.react.devsupport.DevSupportManagerImpl",
                "com.facebook.react.devsupport.DevSupportManagerBase",
                "com.facebook.react.devsupport.DefaultDevSupportManager"
            )
            
            var foundAny = false
            possibleClasses.forEach { className ->
                try {
                    val clazz = packageParam.classLoader.loadClass(className)
                    Log.e("Found class: $className")
                    hookDevSupportManager(clazz, context)
                    foundAny = true
                } catch (e: Exception) {
                    Log.e("Class not found: $className - ${e.message}")
                }
            }
            
            if (!foundAny) {
                tryFindDevSupportClasses(context)
            }
        } catch (e: Exception) {
        }
    }
    
    private fun tryFindDevSupportClasses(context: Context) {
        try {
            val dexFile = packageParam.classLoader.javaClass.getDeclaredField("pathList")
            dexFile.isAccessible = true
            Log.e("Searching for DevSupport classes in classloader...")
        } catch (e: Exception) {
            Log.e("Could not search for classes: ${e.message}")
        }
    }

    private fun hookDevSupportManager(clazz: Class<*>, context: Context) {
        Log.e("Attempting to hook ${clazz.name}")
        
        // List all methods to see what's available
        Log.e("Available methods in ${clazz.simpleName}:")
        clazz.methods.forEach { method ->
            if (method.name.contains("Dev") || method.name.contains("Reload") || method.name.contains("Options")) {
                Log.e("  - ${method.name}")
            }
        }
        
        try {
            try {
                val handleReloadJSMethod = clazz.methods.firstOrNull { it.name == "handleReloadJS" }
                if (handleReloadJSMethod != null) {
                    XposedBridge.hookMethod(handleReloadJSMethod, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? {
                            Log.e("handleReloadJS called - reloading app")
                            reloadApp()
                            return null
                        }
                    })
                } else {
                }
            } catch (e: Exception) {
                Log.e("Failed to hook handleReloadJS: ${e.message}")
            }

            try {
                val showDevOptionsDialogMethod = clazz.methods.firstOrNull { it.name == "showDevOptionsDialog" }
                if (showDevOptionsDialogMethod != null) {
                    XposedBridge.hookMethod(showDevOptionsDialogMethod, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? {
                            try {
                                var activityContext: Context? = null
                                try {
                                    activityContext = getContextFromDevSupport(clazz, param.thisObject)
                                    if (activityContext != null) {
                                        Log.e("Successfully got context from DevSupport")
                                    }
                                } catch (e: Exception) {
                                    Log.e("Failed to get context from DevSupport (non-fatal): ${e.message}")
                                }
                                
                                val finalContext = activityContext ?: contextForMenu ?: context
                                Log.e("Using context: $finalContext (type: ${finalContext.javaClass.name})")
                                
                                showRecoveryMenu(finalContext)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            return null
                        }
                    })
                } else {
                }
            } catch (e: Exception) {
            }
        } catch (e: Exception) {
        }
    }

    private fun getContextFromDevSupport(clazz: Class<*>, instance: Any?): Context? {
        if (instance == null) {
            Log.e("getContextFromDevSupport: instance is null")
            return null
        }
        
        return try {
            
            // what if we just did this and searched for literally everything
            val helpers = listOf(
                "mReactInstanceDevHelper",
                "reactInstanceDevHelper",
                "mReactInstanceManager",
                "mApplicationContext"
            )
            
            for (helperName in helpers) {
                try {
                    Log.e("Trying field: $helperName")
                    val helperField = XposedHelpers.findFieldIfExists(clazz, helperName)
                    if (helperField == null) {
                        Log.e("Field $helperName not found, skipping")
                        continue
                    }
                    
                    val helper = helperField.get(instance)
                    if (helper == null) {
                        Log.e("Field $helperName is null, skipping")
                        continue
                    }
                    
                    if (helper is Context) {
                        Log.e("Field $helperName is a Context, returning it")
                        return helper
                    }
                    
                    val getCurrentActivityMethod = helper.javaClass.methods.firstOrNull { 
                        it.name == "getCurrentActivity" 
                    }
                    
                    if (getCurrentActivityMethod != null) {
                        val ctx = getCurrentActivityMethod.invoke(helper) as? Context
                        if (ctx != null) {
                            Log.e("Got context from $helperName.getCurrentActivity()")
                            return ctx
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Error trying $helperName: ${e.message}")
                }
            }
            
            Log.e("Could not get context from DevSupport object using any method")
            null
        } catch (e: Exception) {
            Log.e("Failed to get context (outer catch): ${e.message}")
            null
        }
    }

    private fun showRecoveryMenu(context: Context) {
        Log.e("showRecoveryMenu called with context: $context")
        try {
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
            Log.e("Recovery menu shown successfully")
        } catch (e: Exception) {
            Log.e("Error showing recovery menu: ${e.message}", e)
            throw e
        }
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