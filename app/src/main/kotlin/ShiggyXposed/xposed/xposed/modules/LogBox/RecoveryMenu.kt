package ShiggyXposed.xposed.modules.LogBox

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout
import ShiggyXposed.xposed.Utils.Log

object RecoveryMenu {

    fun showRecoveryMenu(context: Context) {
        Log.e("showRecoveryMenu called with context: $context")
        try {
            val colors = LogBoxTheme.getM3Colors(context)
            lateinit var dialog: AlertDialog

            val container = LogBoxComponents.createMenuContainer(context, colors)

            container.addView(LogBoxComponents.createTitle(context, colors, "ShiggyXposed Recovery", center = true))
            container.addView(LogBoxComponents.createSubtitle(context, colors, "Select an option to continue"))

            val safeModeText = if (LogBoxUtils.isSafeModeEnabled(context)) "Disable Safe Mode" else "Enable Safe Mode"
            container.addView(LogBoxComponents.createM3Button(context, safeModeText, colors) {
                dialog.dismiss()
                handleMenuSelection(context, 0)
            })

            container.addView(LogBoxComponents.createM3Button(context, "Load Custom Bundle", colors) {
                dialog.dismiss()
                handleMenuSelection(context, 2)
            })

            container.addView(LogBoxComponents.createM3Button(context, "Reload App", colors) {
                dialog.dismiss()
                handleMenuSelection(context, 3)
            })

            val optionsBtn = LogBoxComponents.createM3Button(context, "Options", colors) {
                dialog.dismiss()
                handleMenuSelection(context, 5)
            }
            (optionsBtn.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.setMargins(lp.leftMargin, LogBoxUtils.dpToPx(context, 16), lp.rightMargin, lp.bottomMargin)
                optionsBtn.layoutParams = lp
            }
            container.addView(optionsBtn)

            dialog = LogBoxDialogs.createDialog(context, container)
            LogBoxDialogs.currentMenuContainer = container
            dialog.show()
            LogBoxDialogs.setDialogWindowWidth(dialog, context)
            Log.e("Recovery menu shown successfully")
        } catch (e: Exception) {
            Log.e("Error showing recovery menu: ${e.message}", e)
            throw e
        }
    }

    private fun handleMenuSelection(context: Context, index: Int) {
        when (index) {
            0 -> LogBoxActions.toggleSafeMode(context)
            1 -> showConfirmAction(context, "Refetch Bundle", "This will download the latest bundle from Github.") {
                LogBoxActions.refetchBundle(context)
            }

            2 -> CustomBundleDialog.showCustomBundleDialog(context)
            3 -> ShiggyXposed.xposed.Utils.Companion.reloadApp()
            4 -> showConfirmAction(
                context,
                "Clear Cache & Reset",
                "This will clear all cached bundles and reset to default settings."
            ) {
                LogBoxActions.clearCacheAndReset(context)
            }

            5 -> OptionsMenu.showOptionsMenu(context)
        }
    }

    private fun showConfirmAction(context: Context, title: String, message: String, action: () -> Unit) {
        val colors = LogBoxTheme.getM3Colors(context)
        val container = LogBoxComponents.createMenuContainer(context, colors)

        container.addView(LogBoxComponents.createTitle(context, colors, title))

        val messageView = android.widget.TextView(context).apply {
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
            gravity = android.view.Gravity.END
        }

        val cancelButton = android.widget.TextView(context).apply {
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

        val confirmButton = android.widget.TextView(context).apply {
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

        val dialog = LogBoxDialogs.createDialog(context, container)

        cancelButton.setOnClickListener { dialog.dismiss() }
        confirmButton.setOnClickListener {
            dialog.dismiss()
            action()
        }

        buttonContainer.addView(cancelButton)
        buttonContainer.addView(confirmButton)
        container.addView(buttonContainer)

        LogBoxDialogs.currentMenuContainer = container
        dialog.show()
        LogBoxDialogs.setDialogWindowWidth(dialog, context)
    }
}
