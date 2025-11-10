package ShiggyXposed.xposed.modules

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.EditText
import android.widget.Toast
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ShiggyXposed.xposed.BuildConfig
import ShiggyXposed.xposed.Module
import ShiggyXposed.xposed.Utils
import ShiggyXposed.xposed.Utils.Log
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import ShiggyXposed.xposed.Utils.Companion.reloadApp

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
            "Reload App"
        )

        // Try to get dark mode colors from your resources
        val surfaceColorId = context.resources.getIdentifier("md_theme_dark_surface", "color", context.packageName)
        val onSurfaceColorId = context.resources.getIdentifier("md_theme_dark_onSurface", "color", context.packageName)
        val primaryColorId = context.resources.getIdentifier("md_theme_dark_primary", "color", context.packageName)

        val onSurfaceColor = if (onSurfaceColorId != 0) context.getColor(onSurfaceColorId) else null
        val primaryColor = if (primaryColorId != 0) context.getColor(primaryColorId) else null
        val surfaceColor = if (surfaceColorId != 0) context.getColor(surfaceColorId) else null

        // Custom adapter to set text and background color for items
        val adapter = object : android.widget.ArrayAdapter<String>(context, android.R.layout.select_dialog_item, options) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                (view as? android.widget.TextView)?.setTextColor(android.graphics.Color.WHITE)
                view.setBackgroundColor(android.graphics.Color.BLACK)
                return view
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("ShiggyXposed Recovery Menu")
            .setAdapter(adapter) { _, which ->
                handleMenuSelection(context, which)
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            // Set button text color to white
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.BLACK)
                textSize = 14f
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.BLACK)
                textSize = 14f
            }
            // Set dialog background to black
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            // Set title text color to white
            val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
            val titleView = dialog.findViewById<android.widget.TextView>(titleId)
            titleView?.setTextColor(android.graphics.Color.WHITE)
            // Set ListView background to black
            dialog.listView?.setBackgroundColor(android.graphics.Color.BLACK)
        }
        dialog.show()
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
        val dialog = AlertDialog.Builder(context)
            .setTitle("Confirm Action")
            .setMessage("Are you sure you want to $actionText?")
            .setPositiveButton("Confirm") { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.BLACK)
                textSize = 14f
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.BLACK)
                textSize = 14f
            }
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
            val titleView = dialog.findViewById<android.widget.TextView>(titleId)
            titleView?.setTextColor(android.graphics.Color.WHITE)
            // Set message text color to white
            val messageId = context.resources.getIdentifier("message", "id", "android")
            val messageView = dialog.findViewById<android.widget.TextView>(messageId)
            messageView?.setTextColor(android.graphics.Color.WHITE)
        }
        dialog.show()
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
                config.put("customLoadUrl", "http://localhost:4040/ShiggyCord.js")
                configFile.writeText(config.toString())
            }

            reloadApp()
        } catch (e: Exception) {
            Log.e("Error resetting bundle: ${e.message}")
            showError(context, "Failed to reset bundle", e.message)
        }
    }

    private fun loadCustomBundle(context: Context) {
        val filesDir = java.io.File(context.filesDir, "pyoncord")
        val configFile = java.io.File(filesDir, "loader.json")
        var currentUrl: String? = null

        // Try to read the current custom URL from loader.json
        if (configFile.exists()) {
            try {
                val json = org.json.JSONObject(configFile.readText())
                val customLoadUrl = json.optJSONObject("customLoadUrl")
                if (customLoadUrl != null && customLoadUrl.optBoolean("enabled", false)) {
                    currentUrl = customLoadUrl.optString("url", "")
                }
            } catch (_: Exception) {}
        }

        val urlInput = EditText(context).apply {
            hint = "http://localhost:4040/ShiggyCord.js"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.BLACK)
            setHintTextColor(android.graphics.Color.GRAY)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(currentUrl ?: "")
            gravity = android.view.Gravity.START
            textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
            setPadding(
                (24 * context.resources.displayMetrics.density).toInt(), // left
                paddingTop,
                (24 * context.resources.displayMetrics.density).toInt(), // right
                paddingBottom
            )
        }
        val container = android.widget.FrameLayout(context)
        val params = android.widget.FrameLayout.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        params.leftMargin = (16 * context.resources.displayMetrics.density).toInt()
        params.rightMargin = (16 * context.resources.displayMetrics.density).toInt()
        urlInput.layoutParams = params
        container.addView(urlInput)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Load Custom Bundle")
            .setView(container)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.BLACK)
                textSize = 14f
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.BLACK)
                textSize = 14f
            }
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
            val titleView = dialog.findViewById<android.widget.TextView>(titleId)
            titleView?.setTextColor(android.graphics.Color.WHITE)
        }

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            try {
                val url = urlInput.text?.toString()?.trim() ?: ""
                val enabled = url.isNotEmpty()
                val escapedUrl = url.replace("\\", "\\\\").replace("\"", "\\\"")
                val json = "{\"customLoadUrl\":{\"enabled\":$enabled,\"url\":\"$escapedUrl\"}}"
                configFile.writeText(json)
                java.io.File(filesDir, "bundle.js").delete()
                android.widget.Toast.makeText(
                    context,
                    if (enabled) "Custom bundle URL saved and enabled." else "Custom bundle URL removed and disabled.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                reloadApp()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Failed to set custom bundle: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
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
            val configFile = File(pyoncordDir, "loader.json")

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
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message ?: "An unknown error occurred")
            .setPositiveButton("OK", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.BLACK)
                textSize = 14f
            }
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
            val titleView = dialog.findViewById<android.widget.TextView>(titleId)
            titleView?.setTextColor(android.graphics.Color.WHITE)
            val messageId = context.resources.getIdentifier("message", "id", "android")
            val messageView = dialog.findViewById<android.widget.TextView>(messageId)
            messageView?.setTextColor(android.graphics.Color.WHITE)
        }
        dialog.show()
    }

    override fun onContext(context: Context) {
    }
}
