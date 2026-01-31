package ShiggyXposed.xposed.modules.LogBox

import android.app.AlertDialog
import android.content.Context

object FlavorSelectionMenu {

    fun showFlavorSelection(context: Context) {
        val colors = LogBoxTheme.getM3Colors(context)
        lateinit var dialog: AlertDialog

        val container = LogBoxComponents.createMenuContainer(context, colors, 20)
        container.addView(LogBoxComponents.createTitle(context, colors, "Select Menu Flavor", center = true))

        LogBoxConstants.FLAVORS.forEach { flavor ->
            container.addView(
                LogBoxComponents.createM3Button(
                    context,
                    flavor.replaceFirstChar { it.uppercase() },
                    colors
                ) {
                    try {
                        dialog.dismiss()
                    } catch (_: Exception) {
                    }
                    LogBoxUtils.saveMenuFlavor(context, flavor)
                })
        }

        dialog = LogBoxDialogs.createDialog(context, container)
        LogBoxDialogs.currentMenuContainer = container
        dialog.show()
        LogBoxDialogs.setDialogWindowWidth(dialog, context)
    }
}
