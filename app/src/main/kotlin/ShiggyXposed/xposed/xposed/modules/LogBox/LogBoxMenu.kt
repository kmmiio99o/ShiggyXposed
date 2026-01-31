package ShiggyXposed.xposed.modules.LogBox

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.animation.*
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.content.res.ColorStateList
import android.view.animation.DecelerateInterpolator
import org.json.JSONObject
import java.io.File
import ShiggyXposed.xposed.Utils.Log

object LogBoxMenu {

    private var currentMenuContainer: LinearLayout? = null

    fun showRecoveryMenu(context: Context) {
        Log.e("showRecoveryMenu called with context: $context")
        try {
            val colors = LogBoxTheme.getM3Colors(context)
            lateinit var dialog: AlertDialog

            val container = createMenuContainer(context, colors)

            // Title
            container.addView(createTitle(context, colors, "ShiggyXposed Recovery", center = true))

            // Subtitle
            container.addView(createSubtitle(context, colors, "Select an option to continue"))

            // Safe Mode Button
            val safeModeText = if (LogBoxUtils.isSafeModeEnabled(context)) "Disable Safe Mode" else "Enable Safe Mode"
            container.addView(createM3Button(context, safeModeText, colors) {
                dialog.dismiss()
                handleMenuSelection(context, 0)
            })

            // Load Custom Bundle Button
            container.addView(createM3Button(context, "Load Custom Bundle", colors) {
                dialog.dismiss()
                handleMenuSelection(context, 2)
            })

            // Reload App Button
            container.addView(createM3Button(context, "Reload App", colors) {
                dialog.dismiss()
                handleMenuSelection(context, 3)
            })

            // Options Button
            val optionsBtn = createM3Button(context, "Options", colors) {
                dialog.dismiss()
                handleMenuSelection(context, 5)
            }
            (optionsBtn.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.setMargins(lp.leftMargin, LogBoxUtils.dpToPx(context, 16), lp.rightMargin, lp.bottomMargin)
                optionsBtn.layoutParams = lp
            }
            container.addView(optionsBtn)

            dialog = createDialog(context, container)
            currentMenuContainer = container
            dialog.show()
            setDialogWindowWidth(dialog, context)
            Log.e("Recovery menu shown successfully")
        } catch (e: Exception) {
            Log.e("Error showing recovery menu: ${e.message}", e)
            throw e
        }
    }

    fun showOptionsMenu(context: Context) {
        val colors = LogBoxTheme.getM3Colors(context)
        lateinit var dialog: AlertDialog

        val container = createMenuContainer(context, colors, 20)

        container.addView(createTitle(context, colors, "Options", center = true))

        // Themes button -> opens Themes submenu
        container.addView(createM3Button(context, "Themes", colors) {
            dialog.dismiss()
            showThemesMenu(context)
        })

        // Disable/Enable bundle injection
        val injectionDisabled = LogBoxUtils.isBundleInjectionDisabled(context)
        val injectionText = if (injectionDisabled) "Enable Bundle Injection" else "Disable Bundle Injection"
        container.addView(createM3Button(context, injectionText, colors) {
            dialog.dismiss()
            LogBoxActions.toggleBundleInjection(context)
        })

        container.addView(createM3Button(context, "Refetch Bundle", colors) {
            dialog.dismiss()
            showConfirmAction(context, "Refetch Bundle", "This will download the latest bundle from Github.") {
                LogBoxActions.refetchBundle(context)
            }
        })

        container.addView(createM3Button(context, "Clear Cache & Reset", colors) {
            dialog.dismiss()
            showConfirmAction(
                context,
                "Clear Cache & Reset",
                "This will clear all cached bundles and reset to default settings."
            ) {
                LogBoxActions.clearCacheAndReset(context)
            }
        })

        container.addView(createM3Button(context, "Back", colors) {
            dialog.dismiss()
            showRecoveryMenu(context)
        })

        dialog = createDialog(context, container)
        currentMenuContainer = container
        dialog.show()
        setDialogWindowWidth(dialog, context)
    }

    fun showThemesMenu(context: Context) {
        val colors = LogBoxTheme.getM3Colors(context)
        lateinit var dialog: AlertDialog

        val container = createMenuContainer(context, colors, 20)

        container.addView(createTitle(context, colors, "Themes", center = true))

        // Segmented appearance selector
        container.addView(createAppearanceSelector(context, colors))

        // Flavor selection
        container.addView(createM3Button(context, "Menu Color Flavor", colors) {
            dialog.dismiss()
            showFlavorSelection(context)
        })

        // Back button (return to Options)
        container.addView(createM3Button(context, "Back", colors) {
            dialog.dismiss()
            showOptionsMenu(context)
        })

        dialog = createDialog(context, container)
        currentMenuContainer = container
        dialog.show()
        setDialogWindowWidth(dialog, context)
    }

    fun showFlavorSelection(context: Context) {
        val colors = LogBoxTheme.getM3Colors(context)
        lateinit var dialog: AlertDialog

        val container = createMenuContainer(context, colors, 20)

        container.addView(createTitle(context, colors, "Select Menu Flavor", center = true))

        LogBoxConstants.FLAVORS.forEach { flavor ->
            container.addView(createM3Button(context, flavor.replaceFirstChar { it.uppercase() }, colors) {
                try {
                    dialog.dismiss()
                } catch (_: Exception) {
                }
                LogBoxUtils.saveMenuFlavor(context, flavor)
                showThemesMenu(context)
            })
        }

        dialog = createDialog(context, container)
        currentMenuContainer = container
        dialog.show()
        setDialogWindowWidth(dialog, context)
    }

    fun showConfirmAction(context: Context, title: String, message: String, action: () -> Unit) {
        val colors = LogBoxTheme.getM3Colors(context)

        val container = createMenuContainer(context, colors)

        container.addView(createTitle(context, colors, title))

        val messageView = TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, 0, 0, LogBoxUtils.dpToPx(context, 24))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                lineHeight = LogBoxUtils.dpToPx(context, 20)
            }
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
                LogBoxUtils.dpToPx(context, 16),
                LogBoxUtils.dpToPx(context, 10),
                LogBoxUtils.dpToPx(context, 16),
                LogBoxUtils.dpToPx(context, 10)
            )
        }

        val confirmButton = TextView(context).apply {
            text = "Confirm"
            setTextColor(colors.primary)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(
                LogBoxUtils.dpToPx(context, 16),
                LogBoxUtils.dpToPx(context, 10),
                LogBoxUtils.dpToPx(context, 16),
                LogBoxUtils.dpToPx(context, 10)
            )
        }

        val dialog = createDialog(context, container)

        cancelButton.setOnClickListener { dialog.dismiss() }
        confirmButton.setOnClickListener {
            dialog.dismiss()
            action()
        }

        buttonContainer.addView(cancelButton)
        buttonContainer.addView(confirmButton)
        container.addView(buttonContainer)

        currentMenuContainer = container
        dialog.show()
        setDialogWindowWidth(dialog, context)
    }

    fun showCustomBundleDialog(context: Context) {
        val colors = LogBoxTheme.getM3Colors(context)
        val filesDir = LogBoxUtils.getPyoncordDirectory(context)
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

        val container = createMenuContainer(context, colors)

        container.addView(createTitle(context, colors, "Custom Bundle URL"))

        // Toggle switch container
        val toggleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, LogBoxUtils.dpToPx(context, 16))
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
            background = LogBoxUtils.createM3Background(context, colors.surfaceVariant, 12f)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(currentUrl ?: "")
            textSize = 14f
            setPadding(
                LogBoxUtils.dpToPx(context, 16),
                LogBoxUtils.dpToPx(context, 12),
                LogBoxUtils.dpToPx(context, 16),
                LogBoxUtils.dpToPx(context, 12)
            )
            alpha = if (isEnabled) 1f else 0.5f
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, LogBoxUtils.dpToPx(context, 24))
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
                LogBoxUtils.dpToPx(context, 16),
                LogBoxUtils.dpToPx(context, 10),
                LogBoxUtils.dpToPx(context, 16),
                LogBoxUtils.dpToPx(context, 10)
            )
        }

        val saveButton = TextView(context).apply {
            text = "Save"
            setTextColor(colors.primary)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(
                LogBoxUtils.dpToPx(context, 16),
                LogBoxUtils.dpToPx(context, 10),
                LogBoxUtils.dpToPx(context, 16),
                LogBoxUtils.dpToPx(context, 10)
            )
        }

        val dialog = createDialog(context, container)

        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            try {
                val url = urlInput.text?.toString()?.trim() ?: ""
                val enabled = toggleSwitch.tag as Boolean

                if (enabled && url.isNotEmpty()) {
                    LogBoxActions.setCustomBundleURL(context, url, true)
                    dialog.dismiss()
                } else if (!enabled) {
                    LogBoxActions.setCustomBundleURL(
                        context,
                        url.ifEmpty { "http://localhost:4040/shiggycord.js" },
                        false
                    )
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

        currentMenuContainer = container
        dialog.show()
        setDialogWindowWidth(dialog, context)
    }

    fun showErrorDialog(context: Context, title: String, message: String?) {
        val colors = LogBoxTheme.getM3Colors(context)

        val container = createMenuContainer(context, colors)

        val titleView = TextView(context).apply {
            text = title
            textSize = 20f
            setTextColor(colors.error)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, LogBoxUtils.dpToPx(context, 16))
        }
        container.addView(titleView)

        val messageView = TextView(context).apply {
            text = message ?: "An unknown error occurred"
            textSize = 14f
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, 0, 0, LogBoxUtils.dpToPx(context, 24))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                lineHeight = LogBoxUtils.dpToPx(context, 20)
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
                LogBoxUtils.dpToPx(context, 16),
                LogBoxUtils.dpToPx(context, 10),
                LogBoxUtils.dpToPx(context, 16),
                LogBoxUtils.dpToPx(context, 10)
            )
        }

        val dialog = createDialog(context, container)

        okButton.setOnClickListener { dialog.dismiss() }
        container.addView(okButton)

        currentMenuContainer = container
        dialog.show()
        setDialogWindowWidth(dialog, context)
    }

    // Helper functions
    private fun createMenuContainer(context: Context, colors: M3Colors, padding: Int = 24): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                LogBoxUtils.dpToPx(context, padding),
                LogBoxUtils.dpToPx(context, padding),
                LogBoxUtils.dpToPx(context, padding),
                LogBoxUtils.dpToPx(context, padding)
            )
            background = LogBoxUtils.createM3Background(context, colors.surface, if (padding == 24) 28f else 24f)
        }
    }

    private fun createTitle(context: Context, colors: M3Colors, text: String, center: Boolean = false): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = if (center) 20f else 22f
            setTextColor(colors.onSurface)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, LogBoxUtils.dpToPx(context, if (center) 12 else 16))
            gravity = if (center) Gravity.CENTER else Gravity.START
        }
    }

    private fun createSubtitle(context: Context, colors: M3Colors, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(colors.onSurfaceVariant)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            setPadding(0, 0, 0, LogBoxUtils.dpToPx(context, 24))
            gravity = Gravity.CENTER
        }
    }

    fun createM3Button(context: Context, text: String, colors: M3Colors, onClick: () -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = LogBoxUtils.createM3Background(context, colors.primaryContainer, 20f)
            setPadding(
                LogBoxUtils.dpToPx(context, 24),
                LogBoxUtils.dpToPx(context, 10),
                LogBoxUtils.dpToPx(context, 24),
                LogBoxUtils.dpToPx(context, 10)
            )
            clipToOutline = true
            isClickable = true
            isFocusable = true

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LogBoxUtils.dpToPx(context, 40)
            ).apply {
                setMargins(0, LogBoxUtils.dpToPx(context, 8), 0, 0)
            }
            layoutParams = params

            addView(TextView(context).apply {
                this.text = text
                setTextColor(colors.onPrimaryContainer)
                textSize = 14f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                gravity = Gravity.CENTER
            })

            val rippleDrawable = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Color.argb(30, 255, 255, 255)),
                LogBoxUtils.createM3Background(context, colors.primaryContainer, 20f),
                null
            )
            background = rippleDrawable

            setOnClickListener { onClick() }
        }
    }

    private fun createAppearanceSelector(context: Context, colors: M3Colors): View {
        val currentMode = LogBoxUtils.getAppearanceMode(context)

        val options = listOf("System", "Light", "Dark")
        val keys = listOf("system", "light", "dark")

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
            setPadding(0, 0, 0, LogBoxUtils.dpToPx(context, 8))
        }

        val label = TextView(context).apply {
            text = "Appearance"
            textSize = 14f
            setTextColor(colors.onSurface)
            setPadding(0, 0, 0, LogBoxUtils.dpToPx(context, 8))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        wrapper.addView(label)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LogBoxUtils.dpToPx(context, 40)
            )
            layoutParams = params
        }

        options.forEachIndexed { idx, title ->
            val key = keys[idx]
            val seg = TextView(context).apply {
                text = title
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                val segParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                if (idx > 0) segParams.setMargins(LogBoxUtils.dpToPx(context, 8), 0, 0, 0)
                layoutParams = segParams

                val isSelected = currentMode == key
                background = LogBoxUtils.createM3Background(
                    context,
                    if (isSelected) colors.primaryContainer else colors.surfaceVariant,
                    12f
                )
                setTextColor(if (isSelected) colors.onPrimaryContainer else colors.onSurface)
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    LogBoxUtils.setAppearanceMode(context, key)

                    for (i in 0 until row.childCount) {
                        val child = row.getChildAt(i) as TextView
                        val sel = keys[i] == key
                        child.background = LogBoxUtils.createM3Background(
                            context,
                            if (sel) colors.primaryContainer else colors.surfaceVariant,
                            12f
                        )
                        child.setTextColor(if (sel) colors.onPrimaryContainer else colors.onSurface)
                    }
                }
            }
            row.addView(seg)
        }

        wrapper.addView(row)
        return wrapper
    }

    private fun createM3Switch(context: Context, colors: M3Colors, isChecked: Boolean): View {
        val switchView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = isChecked
            layoutParams = LinearLayout.LayoutParams(
                LogBoxUtils.dpToPx(context, 52),
                LogBoxUtils.dpToPx(context, 32)
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
                setCornerRadius(LogBoxUtils.dpToPx(context, 16).toFloat())
            }
            switchView.background = track
        }

        if (switchView is LinearLayout) {
            val thumb = if (switchView.childCount > 0) {
                switchView.getChildAt(0)
            } else {
                View(context).apply {
                    val size = LogBoxUtils.dpToPx(context, 24)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginStart = LogBoxUtils.dpToPx(context, 4)
                        marginEnd = LogBoxUtils.dpToPx(context, 24)
                    }
                    switchView.addView(this)
                }
            }

            val currentParams = thumb.layoutParams as LinearLayout.LayoutParams
            val targetMarginStart = if (isChecked) LogBoxUtils.dpToPx(context, 24) else LogBoxUtils.dpToPx(context, 4)

            val marginAnimator = ValueAnimator.ofInt(currentParams.marginStart, targetMarginStart)
            marginAnimator.duration = 250
            marginAnimator.interpolator = DecelerateInterpolator()
            marginAnimator.addUpdateListener { animator ->
                val animatedMargin = animator.animatedValue as Int
                val params = thumb.layoutParams as LinearLayout.LayoutParams
                params.marginStart = animatedMargin
                params.marginEnd = LogBoxUtils.dpToPx(context, 28) - animatedMargin
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

    private fun createDialog(context: Context, container: LinearLayout): AlertDialog {
        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()

        dialog.window?.setBackgroundDrawable(
            LogBoxUtils.createM3Background(context, Color.TRANSPARENT, 28f)
        )

        constrainContainerWidth(context, container)
        return dialog
    }

    private fun constrainContainerWidth(context: Context, container: LinearLayout) {
        try {
            val maxW = (context.resources.displayMetrics.widthPixels * 0.9).toInt()
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
            val maxByPercent = (screenW * 0.9).toInt()
            val maxByDp = LogBoxUtils.dpToPx(context, 420)
            val targetW = if (maxByPercent < maxByDp) maxByPercent else maxByDp

            dialog.window?.setLayout(targetW, ViewGroup.LayoutParams.WRAP_CONTENT)

            try {
                currentMenuContainer?.let { container ->
                    val lp = container.layoutParams ?: ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    container.layoutParams = lp
                    try {
                        container.minimumWidth = targetW
                    } catch (_: Exception) {
                    }
                    container.post {
                        try {
                            container.requestLayout()
                            container.invalidate()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    private fun handleMenuSelection(context: Context, index: Int) {
        when (index) {
            0 -> LogBoxActions.toggleSafeMode(context)
            1 -> showConfirmAction(
                context, "Refetch Bundle",
                "This will download the latest bundle from Github."
            ) { LogBoxActions.refetchBundle(context) }

            2 -> showCustomBundleDialog(context)
            3 -> ShiggyXposed.xposed.Utils.Companion.reloadApp()
            4 -> showConfirmAction(
                context, "Clear Cache & Reset",
                "This will clear all cached bundles and reset to default settings."
            ) { LogBoxActions.clearCacheAndReset(context) }

            // Options submenu
            5 -> showOptionsMenu(context)
        }
    }
}
