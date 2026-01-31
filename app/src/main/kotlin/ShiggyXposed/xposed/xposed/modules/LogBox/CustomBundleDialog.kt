package ShiggyXposed.xposed.modules.LogBox

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.*
import org.json.JSONObject
import java.io.File

object CustomBundleDialog {

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

        val container = LogBoxComponents.createMenuContainer(context, colors)
        container.addView(LogBoxComponents.createTitle(context, colors, "Custom Bundle URL"))

        val toggleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val toggleSwitch = LogBoxComponents.createM3Switch(context, colors, isEnabled)

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
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
            LogBoxComponents.updateSwitchAppearance(context, toggleSwitch, colors, newState)

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
            gravity = android.view.Gravity.END
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

        val dialog = LogBoxDialogs.createDialog(context, container)

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

        LogBoxDialogs.currentMenuContainer = container
        dialog.show()
        LogBoxDialogs.setDialogWindowWidth(dialog, context)
    }
}
