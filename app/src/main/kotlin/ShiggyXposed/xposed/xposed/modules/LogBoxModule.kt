package ShiggyXposed.xposed.modules

import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ShiggyXposed.xposed.Module
import ShiggyXposed.xposed.Constants
import ShiggyXposed.xposed.Utils.Companion.reloadApp
import ShiggyXposed.xposed.Utils.Log
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
            val getUseDeveloperSupportMethod =
                dcdReactNativeHostClass.methods.first { it.name == "getUseDeveloperSupport" }

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

    // Material 3 Design Helper Functions
    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun isDarkMode(context: Context): Boolean {
        // Read LogBox-specific appearance override first (logbox/LOGBOX_SETTINGS).
        // Supported values for appearanceMode: "system" | "light" | "dark"
        try {
            val settingsFile = File(context.filesDir, "logbox/LOGBOX_SETTINGS")
            if (settingsFile.exists()) {
                val json = JSONObject(settingsFile.readText())
                val mode = json.optString("appearanceMode", "")
                if (mode == "light") return false
                if (mode == "dark") return true
                // if mode == "system" or empty, fall back to system below
            }
        } catch (e: Exception) {
            Log.e("Error reading logbox appearance settings: ${e.message}")
        }

        return (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun getM3Colors(context: Context): M3Colors {
        val isDark = isDarkMode(context)

        // Base palettes
        val base = if (isDark) {
            M3Colors(
                surface = Color.parseColor("#1C1B1F"),
                surfaceVariant = Color.parseColor("#49454F"),
                onSurface = Color.parseColor("#E6E1E5"),
                onSurfaceVariant = Color.parseColor("#CAC4D0"),
                primary = Color.parseColor("#D0BCFF"),
                onPrimary = Color.parseColor("#381E72"),
                primaryContainer = Color.parseColor("#4F378B"),
                onPrimaryContainer = Color.parseColor("#EADDFF"),
                secondaryContainer = Color.parseColor("#4A4458"),
                onSecondaryContainer = Color.parseColor("#E8DEF8"),
                error = Color.parseColor("#F2B8B5"),
                onError = Color.parseColor("#601410")
            )
        } else {
            M3Colors(
                surface = Color.parseColor("#FFFBFE"),
                surfaceVariant = Color.parseColor("#E7E0EC"),
                onSurface = Color.parseColor("#1C1B1F"),
                onSurfaceVariant = Color.parseColor("#49454F"),
                primary = Color.parseColor("#6750A4"),
                onPrimary = Color.parseColor("#FFFFFF"),
                primaryContainer = Color.parseColor("#EADDFF"),
                onPrimaryContainer = Color.parseColor("#21005D"),
                secondaryContainer = Color.parseColor("#E8DEF8"),
                onSecondaryContainer = Color.parseColor("#1D192B"),
                error = Color.parseColor("#B3261E"),
                onError = Color.parseColor("#FFFFFF")
            )
        }

        // Apply LogBox menu flavor override if present (logbox/LOGBOX_THEMES)
        try {
            val themeFile = File(context.filesDir, "logbox/LOGBOX_THEMES")
            if (themeFile.exists()) {
                val json = JSONObject(themeFile.readText())
                // Support multiple keys that might be written by different UIs/tools:
                val flavor = json.optString("menuFlavor", json.optString("flavor", json.optString("id", ""))).lowercase()
                if (flavor.isNotEmpty()) {
                    val flavorMap = mapOf(
                        "blue" to Pair("#0D47A1", "#82B1FF"),
                        "green" to Pair("#1B5E20", "#A5D6A7"),
                        "mocha" to Pair("#3E2723", "#BCAAA4"),
                        "vanilla" to Pair("#F9A825", "#FFF59D"),
                        "purple" to Pair("#6A1B9A", "#E1BEE7"),
                        "amber" to Pair("#FF6F00", "#FFE0B2"),
                        "teal" to Pair("#004D40", "#80CBC4")
                    )
                    val pair = flavorMap[flavor]
                    if (pair != null) {
                        val primary = Color.parseColor(pair.first)
                        val primaryContainer = Color.parseColor(pair.second)
                        // Keep contrast choices simple: white on primary for most flavors
                        val onPrimary = if (isDark) Color.WHITE else Color.WHITE
                        val onPrimaryContainer = if (isDark) Color.BLACK else Color.BLACK
                        return base.copy(
                            primary = primary,
                            primaryContainer = primaryContainer,
                            onPrimary = onPrimary,
                            onPrimaryContainer = onPrimaryContainer
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Error applying logbox flavor: ${e.message}")
        }

        return base
    }

    private data class M3Colors(
        val surface: Int,
        val surfaceVariant: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val secondaryContainer: Int,
        val onSecondaryContainer: Int,
        val error: Int,
        val onError: Int
    )

    private fun createM3Background(context: Context, color: Int, cornerRadius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            setCornerRadius(dpToPx(context, cornerRadius.toInt()).toFloat())
        }
    }

    private fun createM3Button(context: Context, text: String, colors: M3Colors, onClick: () -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = createM3Background(context, colors.primaryContainer, 20f)
            setPadding(
                dpToPx(context, 24),
                dpToPx(context, 10),
                dpToPx(context, 24),
                dpToPx(context, 10)
            )
            clipToOutline = true
            isClickable = true
            isFocusable = true

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(context, 40)
            ).apply {
                setMargins(0, dpToPx(context, 8), 0, 0)
            }
            layoutParams = params

            addView(TextView(context).apply {
                this.text = text
                setTextColor(colors.onPrimaryContainer)
                textSize = 14f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                gravity = Gravity.CENTER
            })

            // Create a custom ripple effect that stays within bounds
            val rippleDrawable = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Color.argb(30, 255, 255, 255)),
                createM3Background(context, colors.primaryContainer, 20f),
                null
            )
            background = rippleDrawable

            setOnClickListener { onClick() }
        }
    }

    private fun showRecoveryMenu(context: Context) {
        Log.e("showRecoveryMenu called with context: $context")
        try {
            val colors = getM3Colors(context)
            lateinit var dialog: AlertDialog

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dpToPx(context, 24),
                    dpToPx(context, 24),
                    dpToPx(context, 24),
                    dpToPx(context, 24)
                )
                background = createM3Background(context, colors.surface, 28f)
            }

            // Title
            val titleView = TextView(context).apply {
                text = "ShiggyXposed Recovery"
                textSize = 22f
                setTextColor(colors.onSurface)
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dpToPx(context, 16))
                gravity = Gravity.CENTER
            }
            container.addView(titleView)

            // Subtitle
            val subtitleView = TextView(context).apply {
                text = "Select an option to continue"
                textSize = 14f
                setTextColor(colors.onSurfaceVariant)
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                setPadding(0, 0, 0, dpToPx(context, 24))
                gravity = Gravity.CENTER
            }
            container.addView(subtitleView)

            // Safe Mode Button
            val safeModeText = if (isSafeModeEnabled(context)) "Disable Safe Mode" else "Enable Safe Mode"
            container.addView(createM3Button(context, safeModeText, colors) {
                dialog.dismiss()
                handleMenuSelection(context, 0)
            })

            // Refetch Bundle Button
            container.addView(createM3Button(context, "Refetch Bundle", colors) {
                dialog.dismiss()
                handleMenuSelection(context, 1)
            })

            // Load Custom Bundle Button
            container.addView(createM3Button(context, "Load Custom Bundle", colors) {
                dialog.dismiss()
                handleMenuSelection(context, 2)
            })

            // Options Button (opens submenu for theme, injection, flavor)
            container.addView(createM3Button(context, "Options", colors) {
                dialog.dismiss()
                handleMenuSelection(context, 5)
            })

            // Reload App Button
            container.addView(createM3Button(context, "Reload App", colors) {
                dialog.dismiss()
                handleMenuSelection(context, 3)
            })

            // Clear Cache & Reset Button
            container.addView(createM3Button(context, "Clear Cache & Reset", colors) {
                dialog.dismiss()
                handleMenuSelection(context, 4)
            })

            dialog = AlertDialog.Builder(context)
                .setView(container)
                .create()

            dialog.window?.setBackgroundDrawable(
                createM3Background(context, Color.TRANSPARENT, 28f)
            )

            dialog.show()
            Log.e("Recovery menu shown successfully")
        } catch (e: Exception) {
            Log.e("Error showing recovery menu: ${e.message}", e)
            throw e
        }
    }

    private fun handleMenuSelection(context: Context, index: Int) {
        when (index) {
            0 -> toggleSafeMode(context)
            1 -> showConfirmAction(
                context, "Refetch Bundle",
                "This will download the latest bundle from Github."
            ) { refetchBundle(context) }

            2 -> showCustomBundleDialog(context)
            3 -> reloadApp()
            4 -> showConfirmAction(
                context, "Clear Cache & Reset",
                "This will clear all cached bundles and reset to default settings."
            ) { clearCacheAndReset(context) }

            // Options submenu
            5 -> showOptionsMenu(context)
        }
    }

    private fun showConfirmAction(context: Context, title: String, message: String, action: () -> Unit) {
        val colors = getM3Colors(context)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dpToPx(context, 24),
                dpToPx(context, 24),
                dpToPx(context, 24),
                dpToPx(context, 24)
            )
            background = createM3Background(context, colors.surface, 28f)
        }

        val titleView = TextView(context).apply {
            text = title
            textSize = 20f
            setTextColor(colors.onSurface)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(context, 16))
        }
        container.addView(titleView)

        val messageView = TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, 0, 0, dpToPx(context, 24))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                lineHeight = dpToPx(context, 20)
            }
        }
        container.addView(messageView)

        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        // Cancel button
        val cancelButton = TextView(context).apply {
            text = "Cancel"
            setTextColor(colors.primary)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(
                dpToPx(context, 16),
                dpToPx(context, 10),
                dpToPx(context, 16),
                dpToPx(context, 10)
            )
        }

        // Confirm button
        val confirmButton = TextView(context).apply {
            text = "Confirm"
            setTextColor(colors.primary)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(
                dpToPx(context, 16),
                dpToPx(context, 10),
                dpToPx(context, 16),
                dpToPx(context, 10)
            )
        }

        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()

        cancelButton.setOnClickListener { dialog.dismiss() }
        confirmButton.setOnClickListener {
            dialog.dismiss()
            action()
        }

        buttonContainer.addView(cancelButton)
        buttonContainer.addView(confirmButton)
        container.addView(buttonContainer)

        dialog.window?.setBackgroundDrawable(
            createM3Background(context, Color.TRANSPARENT, 28f)
        )

        dialog.show()
    }

    // Options Submenu: Theme mode, disable bundle injection, flavor selection
    private fun showOptionsMenu(context: Context) {
        val colors = getM3Colors(context)
        lateinit var dialog: AlertDialog

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dpToPx(context, 24),
                dpToPx(context, 24),
                dpToPx(context, 24),
                dpToPx(context, 24)
            )
            background = createM3Background(context, colors.surface, 28f)
        }

        val titleView = TextView(context).apply {
            text = "Options"
            textSize = 20f
            setTextColor(colors.onSurface)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(context, 12))
        }
        container.addView(titleView)

        // Theme Mode selection (affects LogBox menu only)
        container.addView(createM3Button(context, "Follow System Theme", colors) {
            dialog.dismiss()
            setAppearanceMode(context, "system")
        })
        container.addView(createM3Button(context, "Force Light Mode", colors) {
            dialog.dismiss()
            setAppearanceMode(context, "light")
        })
        container.addView(createM3Button(context, "Force Dark Mode", colors) {
            dialog.dismiss()
            setAppearanceMode(context, "dark")
        })

        // Disable/Enable bundle injection
        val injectionDisabled = isBundleInjectionDisabled(context)
        val injectionText = if (injectionDisabled) "Enable Bundle Injection" else "Disable Bundle Injection"
        container.addView(createM3Button(context, injectionText, colors) {
            dialog.dismiss()
            toggleBundleInjection(context)
        })

        // Flavor selection
        container.addView(createM3Button(context, "Menu Color Flavor", colors) {
            dialog.dismiss()
            showFlavorSelection(context)
        })

        // Back / Close button
        container.addView(createM3Button(context, "Close", colors) {
            dialog.dismiss()
        })

        dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()

        dialog.window?.setBackgroundDrawable(
            createM3Background(context, Color.TRANSPARENT, 28f)
        )

        dialog.show()
    }

    private fun setAppearanceMode(context: Context, mode: String) {
        try {
            val settingsFile = File(context.filesDir, "logbox/LOGBOX_SETTINGS")
            settingsFile.parentFile?.mkdirs()
            val settings = if (settingsFile.exists()) JSONObject(settingsFile.readText()) else JSONObject()
            settings.put("appearanceMode", mode)
            settingsFile.writeText(settings.toString())
            showM3Toast(context, "Appearance set: ${mode.replaceFirstChar { it.uppercase() }}")
            reloadApp()
        } catch (e: Exception) {
            Log.e("Error setting appearance mode: ${e.message}")
            showError(context, "Failed to set appearance", e.message)
        }
    }

    private fun isBundleInjectionDisabled(context: Context): Boolean {
        return try {
            // Prefer checking the cache location used by HookScriptLoader.
            // If a disabled marker exists next to the bundle in the cache dir, treat injection as disabled.
            val cacheDir = File(context.dataDir, Constants.CACHE_DIR)
            val bundle = File(cacheDir, Constants.MAIN_SCRIPT_FILE)
            val disabled = File(cacheDir, "${Constants.MAIN_SCRIPT_FILE}.disabled")
            if (disabled.exists()) return true
            if (bundle.exists()) return false

            // Fallback to the persisted logbox setting if neither file exists
            val settingsFile = File(context.filesDir, "logbox/LOGBOX_SETTINGS")
            if (!settingsFile.exists()) return false
            val json = JSONObject(settingsFile.readText())
            json.optBoolean("bundleInjectionDisabled", false)
        } catch (e: Exception) {
            Log.e("Error reading injection setting: ${e.message}")
            false
        }
    }

    private fun toggleBundleInjection(context: Context) {
        try {
            // Operate on the cache directory so HookScriptLoader sees the change immediately.
            val cacheDir = File(context.dataDir, Constants.CACHE_DIR)
            cacheDir.mkdirs()
            val bundle = File(cacheDir, Constants.MAIN_SCRIPT_FILE)
            val disabled = File(cacheDir, "${Constants.MAIN_SCRIPT_FILE}.disabled")

            // Persist user intention in logbox settings as well
            val settingsFile = File(context.filesDir, "logbox/LOGBOX_SETTINGS")
            settingsFile.parentFile?.mkdirs()
            val settings = if (settingsFile.exists()) JSONObject(settingsFile.readText()) else JSONObject()

            if (bundle.exists()) {
                // disable injection by renaming the bundle out of the expected filename
                if (disabled.exists()) disabled.delete()
                val renamed = bundle.renameTo(disabled)
                settings.put("bundleInjectionDisabled", true)
                settingsFile.writeText(settings.toString())
                showM3Toast(context, if (renamed) "Bundle injection disabled" else "Bundle injection disabled (marker set)")
            } else if (disabled.exists()) {
                // enable injection by restoring the bundle filename
                val renamed = disabled.renameTo(bundle)
                settings.put("bundleInjectionDisabled", false)
                settingsFile.writeText(settings.toString())
                showM3Toast(context, if (renamed) "Bundle injection enabled" else "Bundle injection enabled (marker removed)")
            } else {
                // Neither exists — create a disabled marker so injection stays disabled until enabled
                disabled.writeText("")
                settings.put("bundleInjectionDisabled", true)
                settingsFile.writeText(settings.toString())
                showM3Toast(context, "Bundle injection disabled (marker created)")
            }

            reloadApp()
        } catch (e: Exception) {
            Log.e("Error toggling bundle injection: ${e.message}")
            showError(context, "Failed to toggle bundle injection", e.message)
        }
    }

    private fun showFlavorSelection(context: Context) {
        val colors = getM3Colors(context)
        val flavors = listOf("blue", "green", "mocha", "vanilla", "purple", "amber", "teal")
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dpToPx(context, 24),
                dpToPx(context, 24),
                dpToPx(context, 24),
                dpToPx(context, 24)
            )
            background = createM3Background(context, colors.surface, 28f)
        }

        val titleView = TextView(context).apply {
            text = "Select Menu Flavor"
            textSize = 18f
            setTextColor(colors.onSurface)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(context, 12))
        }
        container.addView(titleView)

        flavors.forEach { flavor ->
            container.addView(createM3Button(context, flavor.replaceFirstChar { it.uppercase() }, colors) {
                saveMenuFlavor(context, flavor)
            })
        }

        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()

        dialog.window?.setBackgroundDrawable(
            createM3Background(context, Color.TRANSPARENT, 28f)
        )

        dialog.show()
    }

    private fun saveMenuFlavor(context: Context, flavor: String) {
        try {
            val themeFile = File(context.filesDir, "logbox/LOGBOX_THEMES")
            themeFile.parentFile?.mkdirs()

            val themeJson = JSONObject().apply {
                put("menuFlavor", flavor)
            }

            themeFile.writeText(themeJson.toString())
            showM3Toast(context, "Menu flavor set to ${flavor.replaceFirstChar { it.uppercase() }}")
            reloadApp()
        } catch (e: Exception) {
            Log.e("Error saving menu flavor: ${e.message}")
            showError(context, "Failed to save flavor", e.message)
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

            showM3Toast(context, "Safe Mode ${if (newState) "Enabled" else "Disabled"}")
            reloadApp()

        } catch (e: Exception) {
            Log.e("Error toggling safe mode: ${e.message}")
            showError(context, "Failed to toggle safe mode", e.message)
        }
    }

    private fun showM3Toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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

    private fun showCustomBundleDialog(context: Context) {
        val colors = getM3Colors(context)
        val filesDir = File(context.filesDir, "pyoncord")
        val configFile = File(filesDir, "loader.json")
        var currentUrl: String? = null
        var isEnabled = false

        if (configFile.exists()) {
            try {
                val json = JSONObject(configFile.readText())
                val customLoadUrl = json.optJSONObject("customLoadUrl")
                if (customLoadUrl != null) {
                    isEnabled = customLoadUrl.optBoolean("enabled", false)
                    currentUrl = customLoadUrl.optString("url", "")
                }
            } catch (_: Exception) {
            }
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dpToPx(context, 24),
                dpToPx(context, 24),
                dpToPx(context, 24),
                dpToPx(context, 24)
            )
            background = createM3Background(context, colors.surface, 28f)
        }

        val titleView = TextView(context).apply {
            text = "Custom Bundle URL"
            textSize = 20f
            setTextColor(colors.onSurface)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(context, 16))
        }
        container.addView(titleView)

        // Toggle switch container
        val toggleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(context, 16))
            }
            layoutParams = params
        }

        val toggleLabel = TextView(context).apply {
            text = "Enable Custom URL"
            textSize = 14f
            setTextColor(colors.onSurface)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val toggleSwitch = createM3Switch(context, colors, isEnabled)

        toggleContainer.addView(toggleLabel)
        toggleContainer.addView(toggleSwitch)
        container.addView(toggleContainer)

        val urlInput = EditText(context).apply {
            hint = "http://localhost:4040/shiggycord.js"
            setTextColor(colors.onSurface)
            setHintTextColor(colors.onSurfaceVariant)
            background = createM3Background(context, colors.surfaceVariant, 12f)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(currentUrl ?: "")
            textSize = 14f
            setPadding(
                dpToPx(context, 16),
                dpToPx(context, 12),
                dpToPx(context, 16),
                dpToPx(context, 12)
            )
            isEnabled
            alpha = if (isEnabled) 1f else 0.5f
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(context, 24))
            }
            layoutParams = params
        }
        container.addView(urlInput)

        toggleSwitch.setOnClickListener {
            val currentState = toggleSwitch.tag as Boolean
            val newState = !currentState
            toggleSwitch.tag = newState
            updateSwitchAppearance(context, toggleSwitch, colors, newState)

            urlInput.animate()
                .alpha(if (newState) 1f else 0.5f)
                .setDuration(250)
                .withEndAction {
                    urlInput.isEnabled = newState
                }
                .start()
        }

        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val cancelButton = TextView(context).apply {
            text = "Cancel"
            setTextColor(colors.primary)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(
                dpToPx(context, 16),
                dpToPx(context, 10),
                dpToPx(context, 16),
                dpToPx(context, 10)
            )
        }

        val saveButton = TextView(context).apply {
            text = "Save"
            setTextColor(colors.primary)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(
                dpToPx(context, 16),
                dpToPx(context, 10),
                dpToPx(context, 16),
                dpToPx(context, 10)
            )
        }

        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()

        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            try {
                val url = urlInput.text?.toString()?.trim() ?: ""
                val enabled = toggleSwitch.tag as Boolean

                if (enabled && url.isNotEmpty()) {
                    setCustomBundleURL(context, url, true)
                    dialog.dismiss()
                } else if (!enabled) {
                    setCustomBundleURL(context, url.ifEmpty { "http://localhost:4040/shiggycord.js" }, false)
                    dialog.dismiss()
                } else {
                    Toast.makeText(context, "Please enter a URL", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to set custom bundle: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        buttonContainer.addView(cancelButton)
        buttonContainer.addView(saveButton)
        container.addView(buttonContainer)

        dialog.window?.setBackgroundDrawable(
            createM3Background(context, Color.TRANSPARENT, 28f)
        )

        dialog.show()
    }

    private fun createM3Switch(context: Context, colors: M3Colors, isChecked: Boolean): View {
        val switchView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = isChecked
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(context, 52),
                dpToPx(context, 32)
            )
        }

        updateSwitchAppearance(context, switchView, colors, isChecked)
        return switchView
    }

    private fun updateSwitchAppearance(context: Context, switchView: View, colors: M3Colors, isChecked: Boolean) {
        val trackColor = if (isChecked) colors.primary else colors.surfaceVariant
        val thumbColor = if (isChecked) colors.onPrimary else colors.onSurfaceVariant

        val currentTrackColor = if (switchView.background is GradientDrawable) {
            (switchView.background as GradientDrawable).color?.defaultColor
                ?: (if (!isChecked) colors.primary else colors.surfaceVariant)
        } else {
            if (!isChecked) colors.primary else colors.surfaceVariant
        }

        val trackColorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentTrackColor, trackColor)
        trackColorAnimator.duration = 250
        trackColorAnimator.interpolator = DecelerateInterpolator()
        trackColorAnimator.addUpdateListener { animator ->
            val animatedColor = animator.animatedValue as Int
            val track = GradientDrawable().apply {
                setColor(animatedColor)
                setCornerRadius(dpToPx(context, 16).toFloat())
            }
            switchView.background = track
        }

        if (switchView is LinearLayout) {
            val thumb = if (switchView.childCount > 0) {
                switchView.getChildAt(0)
            } else {
                // Create new thumb if none exists
                View(context).apply {
                    val size = dpToPx(context, 24)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginStart = dpToPx(context, 4)
                        marginEnd = dpToPx(context, 24)
                    }
                    switchView.addView(this)
                }
            }

            val currentParams = thumb.layoutParams as LinearLayout.LayoutParams
            val targetMarginStart = if (isChecked) dpToPx(context, 24) else dpToPx(context, 4)

            val marginAnimator = ValueAnimator.ofInt(currentParams.marginStart, targetMarginStart)
            marginAnimator.duration = 250
            marginAnimator.interpolator = DecelerateInterpolator()
            marginAnimator.addUpdateListener { animator ->
                val animatedMargin = animator.animatedValue as Int
                val params = thumb.layoutParams as LinearLayout.LayoutParams
                params.marginStart = animatedMargin
                params.marginEnd = dpToPx(context, 28) - animatedMargin
                thumb.layoutParams = params
            }

            val currentThumbColor = if (thumb.background is GradientDrawable) {
                (thumb.background as GradientDrawable).color?.defaultColor
                    ?: (if (!isChecked) colors.onPrimary else colors.onSurfaceVariant)
            } else {
                if (!isChecked) colors.onPrimary else colors.onSurfaceVariant
            }

            val thumbColorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentThumbColor, thumbColor)
            thumbColorAnimator.duration = 250
            thumbColorAnimator.interpolator = DecelerateInterpolator()
            thumbColorAnimator.addUpdateListener { animator ->
                val animatedColor = animator.animatedValue as Int
                thumb.background = GradientDrawable().apply {
                    setColor(animatedColor)
                    shape = GradientDrawable.OVAL
                }
            }

            val animatorSet = AnimatorSet()
            animatorSet.playTogether(trackColorAnimator, marginAnimator, thumbColorAnimator)
            animatorSet.start()
        }
    }

    private fun setCustomBundleURL(context: Context, url: String, enabled: Boolean) {
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
            customLoadUrl.put("url", "http://localhost:4040/shiggycord.js")
            config.put("customLoadUrl", customLoadUrl)

            configFile.writeText(config.toString())
            Toast.makeText(context, "Custom bundle disabled", Toast.LENGTH_SHORT).show()
            reloadApp()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to disable custom bundle", Toast.LENGTH_SHORT).show()
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
        val colors = getM3Colors(context)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dpToPx(context, 24),
                dpToPx(context, 24),
                dpToPx(context, 24),
                dpToPx(context, 24)
            )
            background = createM3Background(context, colors.surface, 28f)
        }

        val titleView = TextView(context).apply {
            text = title
            textSize = 20f
            setTextColor(colors.error)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(context, 16))
        }
        container.addView(titleView)

        val messageView = TextView(context).apply {
            text = message ?: "An unknown error occurred"
            textSize = 14f
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, 0, 0, dpToPx(context, 24))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lineHeight = dpToPx(context, 20)
            }
        }
        container.addView(messageView)

        val okButton = TextView(context).apply {
            text = "OK"
            setTextColor(colors.primary)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.END
            setPadding(
                dpToPx(context, 16),
                dpToPx(context, 10),
                dpToPx(context, 16),
                dpToPx(context, 10)
            )
        }

        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()

        okButton.setOnClickListener { dialog.dismiss() }
        container.addView(okButton)

        dialog.window?.setBackgroundDrawable(
            createM3Background(context, Color.TRANSPARENT, 28f)
        )

        dialog.show()
    }
}
