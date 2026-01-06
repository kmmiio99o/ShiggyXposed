package io.github.revenge.xposed.modules

import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Companion.reloadApp
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.Constants
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import java.io.File

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

    // material design helper functions
    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun getM3Colors(): M3Colors {
        // color scheme
        return M3Colors(
            surface = Color.parseColor("#000000"),
            surfaceVariant = Color.parseColor("#1A1A1A"),
            onSurface = Color.parseColor("#FFFFFF"),
            onSurfaceVariant = Color.parseColor("#CCCCCC"),
            primary = Color.parseColor("#FFFFFF"),
            onPrimary = Color.parseColor("#000000"),
            primaryContainer = Color.parseColor("#2A2A2A"),
            onPrimaryContainer = Color.parseColor("#FFFFFF"),
            error = Color.parseColor("#FF6B6B")
        )
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
        val error: Int
    )

    private fun createM3Background(context: Context, color: Int, cornerRadius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            setCornerRadius(dpToPx(context, cornerRadius.toInt()).toFloat())
        }
    }

    private fun createButton(context: Context, text: String, colors: M3Colors, onClick: () -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = createM3Background(context, colors.primaryContainer, 12f)
            setPadding(
                dpToPx(context, 32),
                dpToPx(context, 12),
                dpToPx(context, 32),
                dpToPx(context, 12)
            )
            isClickable = true
            isFocusable = true

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(context, 48)
            ).apply {
                setMargins(0, dpToPx(context, 8), 0, 0)
            }
            layoutParams = params

            addView(TextView(context).apply {
                this.text = text
                setTextColor(colors.onPrimaryContainer)
                textSize = 15f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                gravity = Gravity.CENTER
            })

            // Ripple effect
            val rippleDrawable = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Color.argb(40, 255, 255, 255)),
                createM3Background(context, colors.primaryContainer, 12f),
                null
            )
            background = rippleDrawable

            setOnClickListener { onClick() }
        }
    }

    private fun showRecoveryMenu(context: Context) {
        Log.e("showRecoveryMenu called with context: $context")
        try {
            val colors = getM3Colors()
            lateinit var dialog: AlertDialog

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dpToPx(context, 24),
                    dpToPx(context, 24),
                    dpToPx(context, 24),
                    dpToPx(context, 24)
                )
                background = createM3Background(context, colors.surface, 24f)
            }

            val titleView = TextView(context).apply {
                text = "KettuXposed Recovery"
                textSize = 20f
                setTextColor(colors.onSurface)
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dpToPx(context, 24))
                gravity = Gravity.CENTER
            }
            container.addView(titleView)

            // safe Mode Button
            val safeModeText = if (isSafeModeEnabled(context)) "Disable Safe Mode" else "Enable Safe Mode"
            container.addView(createButton(context, safeModeText, colors) {
                dialog.dismiss()
                toggleSafeMode(context)
            })

            // load Custom Bundle Button
            container.addView(createButton(context, "Load Custom Bundle", colors) {
                dialog.dismiss()
                showCustomBundleDialog(context)
            })

            // refetch Bundle Button
            container.addView(createButton(context, "Refetch Bundle", colors) {
                dialog.dismiss()
                showConfirmAction(
                    context, "Refetch Bundle",
                    "This will download the latest bundle from Github."
                ) { refetchBundle(context) }
            })

            // toggle bundle injection
            val bundleInjectionText = if (io.github.revenge.xposed.modules.UpdaterModule.isInjectionDisabled(context)) {
                "Enable Bundle Injection"
            } else {
                "Disable Bundle Injection"
            }
            container.addView(createButton(context, bundleInjectionText, colors) {
                dialog.dismiss()
                showConfirmAction(
                    context,
                    if (io.github.revenge.xposed.modules.UpdaterModule.isInjectionDisabled(context)) "Enable Bundle Injection" else "Disable Bundle Injection",
                    "This will ${if (io.github.revenge.xposed.modules.UpdaterModule.isInjectionDisabled(context)) "enable" else "disable"} executing the cached/downloaded bundle. The app will reload to apply the change."
                ) {
                    val currentlyDisabled = io.github.revenge.xposed.modules.UpdaterModule.isInjectionDisabled(context)
                    io.github.revenge.xposed.modules.UpdaterModule.setDisableInjection(context, !currentlyDisabled)
                    reloadApp()
                }
            })

            // reload App Button
            container.addView(createButton(context, "Reload App", colors) {
                dialog.dismiss()
                reloadApp()
            })

            dialog = AlertDialog.Builder(context)
                .setView(container)
                .create()

            dialog.window?.setBackgroundDrawable(
                createM3Background(context, Color.TRANSPARENT, 24f)
            )

            constrainContainerWidth(context, container)
            dialog.show()
            setDialogWindowWidth(dialog, context)
            Log.e("Recovery menu shown successfully")
        } catch (e: Exception) {
            Log.e("Error showing recovery menu: ${e.message}", e)
            throw e
        }
    }

    private fun showConfirmAction(context: Context, title: String, message: String, action: () -> Unit) {
        val colors = getM3Colors()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dpToPx(context, 24),
                dpToPx(context, 24),
                dpToPx(context, 24),
                dpToPx(context, 24)
            )
            background = createM3Background(context, colors.surface, 24f)
        }

        val titleView = TextView(context).apply {
            text = title
            textSize = 18f
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
        }
        container.addView(messageView)

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
                dpToPx(context, 20),
                dpToPx(context, 12),
                dpToPx(context, 20),
                dpToPx(context, 12)
            )
            isClickable = true
        }

        val confirmButton = TextView(context).apply {
            text = "Confirm"
            setTextColor(colors.primary)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(
                dpToPx(context, 20),
                dpToPx(context, 12),
                dpToPx(context, 20),
                dpToPx(context, 12)
            )
            isClickable = true
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
            createM3Background(context, Color.TRANSPARENT, 24f)
        )

        constrainContainerWidth(context, container)
        dialog.show()
        setDialogWindowWidth(dialog, context)
    }

    private fun showCustomBundleDialog(context: Context) {
        val colors = getM3Colors()
        val filesDir = File(context.filesDir, "pyoncord")
        val configFile = File(filesDir, "loader_config.json")
        var currentUrl: String? = null
        var isEnabled = false

        if (configFile.exists()) {
            try {
                val json = JSONObject(configFile.readText())
                val custom = json.optJSONObject("customLoadUrl")
                if (custom != null) {
                    isEnabled = custom.optBoolean("enabled", false)
                    currentUrl = custom.optString("url", "")
                } else {
                    isEnabled = false
                    currentUrl = ""
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
            background = createM3Background(context, colors.surface, 24f)
        }

        val titleView = TextView(context).apply {
            text = "Custom Bundle URL"
            textSize = 18f
            setTextColor(colors.onSurface)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(context, 16))
        }
        container.addView(titleView)

        val toggleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
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
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        // switch container
        val trackPadding = dpToPx(context, 4)
        val switchTrackWidth = dpToPx(context, 52)
        val switchTrackHeight = dpToPx(context, 32)
        val trackCornerRadius = switchTrackHeight / 2f

        val switchContainer = FrameLayout(context).apply {
            background = createM3Background(context, colors.surfaceVariant, trackCornerRadius)
            layoutParams = LinearLayout.LayoutParams(
                switchTrackWidth,
                switchTrackHeight
            )
            tag = isEnabled
            isClickable = true
        }

        // create the thumb with proper sizing
        val thumbSize = switchTrackHeight - (trackPadding * 2)
        val thumbCornerRadius = thumbSize / 2f

        val switchThumb = View(context).apply {
            background = createM3Background(
                context,
                if (isEnabled) colors.onPrimary else colors.onSurfaceVariant,
                thumbCornerRadius
            )
            val params = FrameLayout.LayoutParams(thumbSize, thumbSize)

            val thumbLeft = if (isEnabled) {
                switchTrackWidth - thumbSize - trackPadding
            } else {
                trackPadding
            }

            params.leftMargin = thumbLeft
            params.topMargin = trackPadding

            layoutParams = params
        }

        if (isEnabled) {
            switchContainer.background = createM3Background(context, colors.primary, trackCornerRadius)
        }

        switchContainer.addView(switchThumb)

        toggleContainer.addView(toggleLabel)
        toggleContainer.addView(switchContainer)
        container.addView(toggleContainer)

        val urlInput = EditText(context).apply {
            hint = "http://localhost:4040/bundle.js"
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
            this.isEnabled = isEnabled
            alpha = if (isEnabled) 1f else 0.5f
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(context, 24))
            }
            layoutParams = params
        }
        container.addView(urlInput)

        switchContainer.setOnClickListener {
            val currentState = switchContainer.tag as Boolean
            val newState = !currentState
            switchContainer.tag = newState

            val thumbParams = switchThumb.layoutParams as FrameLayout.LayoutParams
            val targetLeftMargin = if (newState) {
                switchTrackWidth - thumbSize - trackPadding
            } else {
                trackPadding
            }

            // Animate the thumb movement
            val animator = ValueAnimator.ofInt(thumbParams.leftMargin, targetLeftMargin).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener { valueAnimator ->
                    val animatedValue = valueAnimator.animatedValue as Int
                    thumbParams.leftMargin = animatedValue
                    switchThumb.layoutParams = thumbParams
                }
            }
            animator.start()

            // animate track color change
            val trackAnimator = ValueAnimator.ofObject(
                ArgbEvaluator(),
                if (currentState) colors.primary else colors.surfaceVariant,
                if (newState) colors.primary else colors.surfaceVariant
            ).apply {
                duration = 200
                addUpdateListener { animator ->
                    val color = animator.animatedValue as Int
                    switchContainer.background = createM3Background(context, color, trackCornerRadius)
                }
            }
            trackAnimator.start()

            // animate thumb color change
            val thumbAnimator = ValueAnimator.ofObject(
                ArgbEvaluator(),
                if (currentState) colors.onPrimary else colors.onSurfaceVariant,
                if (newState) colors.onPrimary else colors.onSurfaceVariant
            ).apply {
                duration = 200
                addUpdateListener { animator ->
                    val color = animator.animatedValue as Int
                    switchThumb.background = createM3Background(context, color, thumbCornerRadius)
                }
            }
            thumbAnimator.start()

            urlInput.animate()
                .alpha(if (newState) 1f else 0.5f)
                .setDuration(200)
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
                dpToPx(context, 20),
                dpToPx(context, 12),
                dpToPx(context, 20),
                dpToPx(context, 12)
            )
            isClickable = true
        }

        val saveButton = TextView(context).apply {
            text = "Save"
            setTextColor(colors.primary)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(
                dpToPx(context, 20),
                dpToPx(context, 12),
                dpToPx(context, 20),
                dpToPx(context, 12)
            )
            isClickable = true
        }

        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()

        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            try {
                val url = urlInput.text?.toString()?.trim() ?: ""
                val enabled = switchContainer.tag as Boolean

                if (enabled && url.isNotEmpty()) {
                    setCustomBundleURL(context, url, true)
                    dialog.dismiss()
                } else if (!enabled) {
                    setCustomBundleURL(context, url.ifEmpty { "http://localhost:4040/bundle.js" }, false)
                    dialog.dismiss()
                } else {
                    Toast.makeText(context, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                val err = e.message ?: "unknown error"
                Toast.makeText(context, "Failed to set custom bundle: $err", Toast.LENGTH_LONG).show()
            }
        }

        buttonContainer.addView(cancelButton)
        buttonContainer.addView(saveButton)
        container.addView(buttonContainer)

        dialog.window?.setBackgroundDrawable(
            createM3Background(context, Color.TRANSPARENT, 24f)
        )

        constrainContainerWidth(context, container)
        dialog.show()
        setDialogWindowWidth(dialog, context)
    }

    private fun setCustomBundleURL(context: Context, url: String, enabled: Boolean) {
        try {
            val filesDir = File(context.dataDir, Constants.FILES_DIR)
            filesDir.mkdirs()
            val configFile = File(filesDir, "loader.json")

            val cfg = io.github.revenge.xposed.modules.LoaderConfig(
                io.github.revenge.xposed.modules.CustomLoadUrl(enabled, url)
            )

            val jsonText = io.github.revenge.xposed.Utils.JSON.encodeToString(cfg)
            configFile.writeText(jsonText)

            // remove cached bundle so UpdaterModule will fetch the custom one
            val cacheBundle = File(context.dataDir, "${Constants.CACHE_DIR}/${Constants.MAIN_SCRIPT_FILE}")
            if (enabled) {
                if (cacheBundle.exists()) cacheBundle.delete()
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

    private fun getPyoncordDirectory(context: Context): File {
        val dir = File(context.filesDir, "pyoncord")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun constrainContainerWidth(context: Context, container: LinearLayout) {
        try {
            val maxW = (context.resources.displayMetrics.widthPixels * 0.88).toInt()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            container.layoutParams = lp
            try {
                container.minimumWidth = maxW
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    private fun setDialogWindowWidth(dialog: AlertDialog, context: Context) {
        try {
            val screenW = context.resources.displayMetrics.widthPixels
            val maxByPercent = (screenW * 0.88).toInt()
            val maxByDp = dpToPx(context, 380)
            val targetW = if (maxByPercent < maxByDp) maxByPercent else maxByDp

            dialog.window?.setLayout(targetW, LinearLayout.LayoutParams.WRAP_CONTENT)
        } catch (_: Exception) {
        }
    }

    private fun showError(context: Context, title: String, message: String?) {
        val colors = getM3Colors()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dpToPx(context, 24),
                dpToPx(context, 24),
                dpToPx(context, 24),
                dpToPx(context, 24)
            )
            background = createM3Background(context, colors.surface, 24f)
        }

        val titleView = TextView(context).apply {
            text = title
            textSize = 18f
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
        }
        container.addView(messageView)

        val okButton = TextView(context).apply {
            text = "OK"
            setTextColor(colors.primary)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.END
            setPadding(
                dpToPx(context, 20),
                dpToPx(context, 12),
                dpToPx(context, 20),
                dpToPx(context, 12)
            )
            isClickable = true
        }

        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()

        okButton.setOnClickListener { dialog.dismiss() }
        container.addView(okButton)

        dialog.window?.setBackgroundDrawable(
            createM3Background(context, Color.TRANSPARENT, 24f)
        )

        constrainContainerWidth(context, container)
        dialog.show()
        setDialogWindowWidth(dialog, context)
    }
}
