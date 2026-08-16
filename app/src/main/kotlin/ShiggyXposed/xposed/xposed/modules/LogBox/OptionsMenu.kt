package ShiggyXposed.xposed.modules.LogBox

import android.app.AlertDialog
import android.content.Context

object OptionsMenu {

    fun showOptionsMenu(context: Context) {
        val colors = LogBoxTheme.getM3Colors(context)
        lateinit var dialog: AlertDialog

        val container = LogBoxComponents.createMenuContainer(context, colors, 20)
        container.addView(LogBoxComponents.createTitle(context, colors, "Options", center = true))



        val injectionDisabled = LogBoxUtils.isBundleInjectionDisabled(context)
        val injectionText = if (injectionDisabled) "Enable Bundle Injection" else "Disable Bundle Injection"
        container.addView(LogBoxComponents.createM3Button(context, injectionText, colors) {
            dialog.dismiss()
            LogBoxActions.toggleBundleInjection(context)
        })

        container.addView(LogBoxComponents.createM3Button(context, "Refetch Bundle", colors) {
            dialog.dismiss()
            RecoveryMenu::class.java.getDeclaredMethod(
                "showConfirmAction",
                Context::class.java,
                String::class.java,
                String::class.java,
                Runnable::class.java
            ).apply { isAccessible = true }.invoke(
                null,
                context,
                "Refetch Bundle",
                "This will download the latest bundle from Github.",
                Runnable { LogBoxActions.refetchBundle(context) }
            )
        })

        container.addView(LogBoxComponents.createM3Button(context, "Clear Cache & Reset", colors) {
            dialog.dismiss()
            RecoveryMenu::class.java.getDeclaredMethod(
                "showConfirmAction",
                Context::class.java,
                String::class.java,
                String::class.java,
                Runnable::class.java
            ).apply { isAccessible = true }.invoke(
                null,
                context,
                "Clear Cache & Reset",
                "This will clear all cached bundles and reset to default settings.",
                Runnable { LogBoxActions.clearCacheAndReset(context) }
            )
        })

        container.addView(LogBoxComponents.createM3Button(context, "Back", colors) {
            dialog.dismiss()
            RecoveryMenu.showRecoveryMenu(context)
        })

        dialog = LogBoxDialogs.createDialog(context, container)
        LogBoxDialogs.currentMenuContainer = container
        dialog.show()
        LogBoxDialogs.setDialogWindowWidth(dialog, context)
    }
}
