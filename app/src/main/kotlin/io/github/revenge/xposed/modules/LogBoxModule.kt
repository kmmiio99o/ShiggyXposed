package GoonXposed.xposed.modules

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.EditText
import android.widget.Toast
import de.robv.android.xposed.callbacks.XC_LoadPackage
import GoonXposed.xposed.BuildConfig
import GoonXposed.xposed.Module
import GoonXposed.xposed.Utils
import GoonXposed.xposed.Utils.Log
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import GoonXposed.xposed.Utils.Companion.reloadApp

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
            val reactInstanceManagerClass =
                packageParam.classLoader.loadClass("com.facebook.react.ReactInstanceManager")
            val reactApplicationClass = packageParam.classLoader.loadClass("com.facebook.react.ReactApplication")

            val app = context.applicationContext

            if (reactApplicationClass.isInstance(app)) {
                val getReactNativeHostMethod = reactApplicationClass.methods.first { it.name == "getReactNativeHost" }
                val reactNativeHost = getReactNativeHostMethod.invoke(app)

                val getReactInstanceManagerMethod =
                    reactNativeHost.javaClass.methods.first { it.name == "getReactInstanceManager" }
                val reactInstanceManager = getReactInstanceManagerMethod.invoke(reactNativeHost)

                if (reactInstanceManager != null) {
                    val reloadMethod =
                        reactInstanceManagerClass.methods.first { it.name == "recreateReactContextInBackground" }
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
            "Reload App",
            "Clear Cache & Reset"
        )

        val adapter =
            object : android.widget.ArrayAdapter<String>(context, android.R.layout.select_dialog_item, options) {
                override fun getView(
                    position: Int,
                    convertView: android.view.View?,
                    parent: android.view.ViewGroup
                ): android.view.View {
                    val view = super.getView(position, convertView, parent)
                    (view as? android.widget.TextView)?.apply {
                        setTextColor(0xFFFFFFFF.toInt())
                        textSize = 14f
                        setPadding(
                            (16 * context.resources.displayMetrics.density).toInt(),
                            (12 * context.resources.displayMetrics.density).toInt(),
                            (16 * context.resources.displayMetrics.density).toInt(),
                            (12 * context.resources.displayMetrics.density).toInt()
                        )
                        setTypeface(typeface, android.graphics.Typeface.NORMAL)
                    }
                    view.setBackgroundColor(0xFF2D2D2D.toInt())
                    return view
                }
            }

        val dialog = AlertDialog.Builder(context)
            .setTitle("GoonXposed Recovery Menu")
            .setAdapter(adapter) { _, which ->
                handleMenuSelection(context, which)
            }
            .setNegativeButton("Close", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(0xFFBB86FC.toInt())
                setBackgroundColor(0xFF1E1E1E.toInt())
                textSize = 12f
                setPadding(
                    (20 * context.resources.displayMetrics.density).toInt(),
                    (8 * context.resources.displayMetrics.density).toInt(),
                    (20 * context.resources.displayMetrics.density).toInt(),
                    (8 * context.resources.displayMetrics.density).toInt()
                )
            }

            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF1E1E1E.toInt()))

            val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
            val titleView = dialog.findViewById<android.widget.TextView>(titleId)
            titleView?.apply {
                setTextColor(0xFFBB86FC.toInt())
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            dialog.listView?.setBackgroundColor(0xFF1E1E1E.toInt())
            dialog.listView?.divider = android.graphics.drawable.ColorDrawable(0xFF444444.toInt())
            dialog.listView?.dividerHeight = 1
        }
        dialog.show()
    }

    private fun handleMenuSelection(context: Context, index: Int) {
        when (index) {
            0 -> toggleSafeMode(context)
            1 -> showConfirmAction(
                context, "Refetch Bundle",
                "This will download the latest bundle from the official server."
            ) { refetchBundle(context) }

            2 -> showCustomBundleDialog(context)
            3 -> reloadApp()
            4 -> showConfirmAction(
                context, "Clear Cache & Reset",
                "This will clear all cached bundles and reset to default settings."
            ) { clearCacheAndReset(context) }
        }
    }

    private fun showConfirmAction(context: Context, title: String, message: String, action: () -> Unit) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Confirm") { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF1E1E1E.toInt())
                textSize = 12f
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(0xFFBB86FC.toInt())
                setBackgroundColor(0xFF1E1E1E.toInt())
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF1E1E1E.toInt()))

            val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
            val titleView = dialog.findViewById<android.widget.TextView>(titleId)
            titleView?.apply {
                setTextColor(0xFFBB86FC.toInt())
                textSize = 16f
            }

            val messageId = context.resources.getIdentifier("message", "id", "android")
            val messageView = dialog.findViewById<android.widget.TextView>(messageId)
            messageView?.apply {
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                setPadding(
                    (5 * context.resources.displayMetrics.density).toInt(),
                    paddingTop,
                    (5 * context.resources.displayMetrics.density).toInt(),
                    paddingBottom
                )
            }
        }
        dialog.show()
    }

    private fun showCustomBundleDialog(context: Context) {
        val filesDir = File(context.filesDir, "pyoncord")
        val configFile = File(filesDir, "loader.json")
        var currentUrl: String? = null

        if (configFile.exists()) {
            try {
                val json = JSONObject(configFile.readText())
                val customLoadUrl = json.optJSONObject("customLoadUrl")
                if (customLoadUrl != null && customLoadUrl.optBoolean("enabled", false)) {
                    currentUrl = customLoadUrl.optString("url", "")
                }
            } catch (_: Exception) {
            }
        }

        val urlInput = EditText(context).apply {
            hint = "http://localhost:4040/gooncord.js"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2D2D2D.toInt())
            setHintTextColor(0xFF888888.toInt())
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(currentUrl ?: "")
            gravity = android.view.Gravity.START
            textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
            textSize = 14f
            setPadding(
                (12 * context.resources.displayMetrics.density).toInt(),
                (10 * context.resources.displayMetrics.density).toInt(),
                (12 * context.resources.displayMetrics.density).toInt(),
                (10 * context.resources.displayMetrics.density).toInt()
            )
        }

        val container = android.widget.FrameLayout(context)
        val params = android.widget.FrameLayout.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.85).toInt(),
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        params.leftMargin = (15 * context.resources.displayMetrics.density).toInt()
        params.rightMargin = (15 * context.resources.displayMetrics.density).toInt()
        urlInput.layoutParams = params
        container.addView(urlInput)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Custom Bundle URL")
            .setView(container)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(0xFFBB86FC.toInt())
                setBackgroundColor(0xFF1E1E1E.toInt())
                textSize = 12f
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(0xFFBB86FC.toInt())
                setBackgroundColor(0xFF1E1E1E.toInt())
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF1E1E1E.toInt()))

            val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
            val titleView = dialog.findViewById<android.widget.TextView>(titleId)
            titleView?.apply {
                setTextColor(0xFFBB86FC.toInt())
                textSize = 16f
            }
        }

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            try {
                val url = urlInput.text?.toString()?.trim() ?: ""
                if (url.isNotEmpty()) {
                    setCustomBundleURL(context, url)
                    dialog.dismiss()
                } else {
                    disableCustomBundle(context)
                    dialog.dismiss()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to set custom bundle: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showError(context: Context, title: String, message: String?) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message ?: "An unknown error occurred")
            .setPositiveButton("Close", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(0xFFBB86FC.toInt())
                setBackgroundColor(0xFF1E1E1E.toInt())
                textSize = 12f
            }

            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF1E1E1E.toInt()))

            val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
            val titleView = dialog.findViewById<android.widget.TextView>(titleId)
            titleView?.setTextColor(0xFFBB86FC.toInt())

            val messageId = context.resources.getIdentifier("message", "id", "android")
            val messageView = dialog.findViewById<android.widget.TextView>(messageId)
            messageView?.setTextColor(0xFFFFFFFF.toInt())
        }
        dialog.show()
    }

    private fun clearCacheAndReset(context: Context) {
        scope.launch(Dispatchers.IO) {
            try {
                val pyoncordDir = getPyoncordDirectory(context)
                val bundleFile = File(pyoncordDir, "bundle.js")
                val configFile = File(pyoncordDir, "loader.json")

                bundleFile.delete()

                if (configFile.exists()) {
                    val config = JSONObject(configFile.readText())
                    val customLoadUrl = config.optJSONObject("customLoadUrl") ?: JSONObject()
                    customLoadUrl.put("enabled", false)
                    customLoadUrl.put("url", "http://localhost:4040/gooncord.js")
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

    private fun disableCustomBundle(context: Context) {
        try {
            val configFile = File(getPyoncordDirectory(context), "loader.json")
            val config = if (configFile.exists()) {
                JSONObject(configFile.readText())
            } else {
                JSONObject().apply {
                    put("loadReactDevTools", false)
                }
            }

            val customLoadUrl = config.optJSONObject("customLoadUrl") ?: JSONObject()
            customLoadUrl.put("enabled", false)
            customLoadUrl.put("url", "http://localhost:4040/gooncord.js")
            config.put("customLoadUrl", customLoadUrl)

            configFile.writeText(config.toString())
            Toast.makeText(context, "Custom bundle disabled", Toast.LENGTH_SHORT).show()
            reloadApp()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to disable custom bundle", Toast.LENGTH_SHORT).show()
        }
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

            if (configFile.exists()) {
                val config = JSONObject(configFile.readText())
                config.put("customLoadUrlEnabled", false)
                config.put("customLoadUrl", "http://localhost:4040/GoonCord.js")
                configFile.writeText(config.toString())
            }

            reloadApp()
        } catch (e: Exception) {
            Log.e("Error resetting bundle: ${e.message}")
            showError(context, "Failed to reset bundle", e.message)
        }
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
            val configFile = File(pyoncordDir, "loader.json")

            val config = if (configFile.exists()) {
                JSONObject(configFile.readText())
            } else {
                JSONObject().apply {
                    put("loadReactDevTools", false)
                }
            }

            val customLoadUrl = config.optJSONObject("customLoadUrl") ?: JSONObject()
            customLoadUrl.put("enabled", true)
            customLoadUrl.put("url", url)
            config.put("customLoadUrl", customLoadUrl)

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

    override fun onContext(context: Context) {
    }
}
