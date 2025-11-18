package io.github.revenge.xposed.modules

import android.content.Context
import de.robv.android.xposed.callbacks.XC_LoadPackage
import cocobo1.pupu.xposed.BuildConfig
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils
import io.github.revenge.xposed.Utils.Companion.reloadApp
import io.github.revenge.xposed.Utils.Log
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.EditText
import android.widget.Toast
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class LogBoxModule : Module() {
    lateinit var packageParam: XC_LoadPackage.LoadPackageParam
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        this@LogBoxModule.packageParam = packageParam
        
        val dcdReactNativeHostClass = classLoader.loadClass("com.discord.bridge.DCDReactNativeHost")
        val getUseDeveloperSupportMethod = dcdReactNativeHostClass.methods.first { it.name == "getUseDeveloperSupport" }
        
        getUseDeveloperSupportMethod.hook {
            before {
                result = true
            }
        }
        
        listOf(
            "com.facebook.react.devsupport.BridgeDevSupportManager",
            "com.facebook.react.devsupport.BridgelessDevSupportManager"
        ).mapNotNull { packageParam.classLoader.safeLoadClass(it) }.forEach { 
            hookDevSupportManager(it) 
        }
        
        return@with
    }
    
    private fun hookDevSupportManager(clazz: Class<*>) {
        val showDevOptionsDialogMethod = clazz.methods.first { it.name == "showDevOptionsDialog" }
        
        showDevOptionsDialogMethod.hook {
            before {
                try {
                    val context = getContextFromDevSupport(clazz, thisObject)
                    if (context != null) {
                        showRecoveryMenu(context)
                    }
                    result = null
                } catch (e: Exception) {
                    Log.e("Failed to show recovery menu: ${e.message}")
                }
            }
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
    
    private fun reloadReactNative(context: Context) {
        try {
            val reactInstanceManagerClass = packageParam.classLoader.loadClass("com.facebook.react.ReactInstanceManager")
            val reactApplicationClass = packageParam.classLoader.loadClass("com.facebook.react.ReactApplication")
            
            val app = context.applicationContext
            
            if (reactApplicationClass.isInstance(app)) {
                val getReactNativeHostMethod = reactApplicationClass.methods.first { it.name == "getReactNativeHost" }
                val reactNativeHost = getReactNativeHostMethod.invoke(app)
                
                val getReactInstanceManagerMethod = reactNativeHost.javaClass.methods.first { it.name == "getReactInstanceManager" }
                val reactInstanceManager = getReactInstanceManagerMethod.invoke(reactNativeHost)
                
                if (reactInstanceManager != null) {
                    val reloadMethod = reactInstanceManagerClass.methods.first { it.name == "recreateReactContextInBackground" }
                    reloadMethod.invoke(reactInstanceManager)
                } else {
                    showError(context, "Error", "Could not get React Instance Manager")
                }
            } else {
                showError(context, "Error", "Application is not a React Application")
            }
        } catch (e: Exception) {
            Log.e("Failed to Reload App: ${e.message}")
            showError(context, "Failed to reload", "Error: ${e.message}")
        }
    }
    
    private fun showRecoveryMenu(context: Context) {
        val options = arrayOf(
            if (isSafeModeEnabled(context)) "Disable Safe Mode" else "Enable Safe Mode",
            "Refetch Bundle",
            "Load Custom Bundle",
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
            1 -> confirmAction(context, "refetch the bundle") { refetchBundle(context) }
            2 -> loadCustomBundle(context)
            3 -> reloadApp()
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
    
    private fun refetchBundle(context: Context) {
        try {
            val pyoncordDir = getPyoncordDirectory(context)
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
    
    private fun resetBundle(context: Context) {
        try {
            val pyoncordDir = getPyoncordDirectory(context)
            val bundleFile = File(pyoncordDir, "bundle.js")
            val configFile = File(pyoncordDir, "loader_config.json")
            
            bundleFile.delete()
            
            // Reset config
            if (configFile.exists()) {
                val config = JSONObject(configFile.readText())
                config.put("customLoadUrlEnabled", false)
                config.put("customLoadUrl", "http://localhost:4040/kettu.js")
                configFile.writeText(config.toString())
            }

            reloadApp()
        } catch (e: Exception) {
            Log.e("Error resetting bundle: ${e.message}")
            showError(context, "Failed to reset bundle", e.message)
        }
    }
    
    private fun loadCustomBundle(context: Context) {
        val input = EditText(context).apply {
            hint = "http://localhost:4040/kettu.js"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        
        AlertDialog.Builder(context)
            .setTitle("Load Custom Bundle")
            .setMessage("Enter the URL for your custom bundle:")
            .setView(input)
            .setPositiveButton("Load") { _, _ ->
                val urlString = input.text.toString().trim()
                if (urlString.isEmpty()) {
                    showError(context, "Invalid URL", "Please enter a URL")
                    return@setPositiveButton
                }
                
                validateAndLoadBundle(context, urlString)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun validateAndLoadBundle(context: Context, urlString: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL(urlString)
                if (url.protocol != "http" && url.protocol != "https") {
                    withContext(Dispatchers.Main) {
                        showError(context, "Invalid URL", "URL must start with http:// or https://")
                    }
                    return@launch
                }
                
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    withContext(Dispatchers.Main) {
                        showError(context, "Error", "Server returned error $responseCode")
                    }
                    return@launch
                }
                
                val contentType = connection.contentType
                if (contentType != null && !contentType.contains("javascript") && !contentType.contains("text")) {
                    withContext(Dispatchers.Main) {
                        showError(context, "Invalid Content", "URL must point to a JavaScript file")
                    }
                    return@launch
                }
                
                // Save custom bundle URL
                withContext(Dispatchers.Main) {
                    setCustomBundleURL(context, urlString)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError(context, "Connection Error", "Could not reach URL: ${e.message}")
                }
            }
        }
    }
    
    private fun setCustomBundleURL(context: Context, url: String) {
        try {
            val pyoncordDir = getPyoncordDirectory(context)
            val configFile = File(pyoncordDir, "loader_config.json")
            
            val config = if (configFile.exists()) {
                JSONObject(configFile.readText())
            } else {
                JSONObject()
            }
            
            config.put("customLoadUrlEnabled", true)
            config.put("customLoadUrl", url)
            configFile.writeText(config.toString())
            
            File(pyoncordDir, "bundle.js").delete()
            
            Toast.makeText(context, "Custom bundle set", Toast.LENGTH_SHORT).show()
            
            reloadApp()
        } catch (e: Exception) {
            Log.e("Error setting custom bundle URL: ${e.message}")
            showError(context, "Failed to save configuration", e.message)
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
    
    override fun onContext(context: Context) {
    }
}