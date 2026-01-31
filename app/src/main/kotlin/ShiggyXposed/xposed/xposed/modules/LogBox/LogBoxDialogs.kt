package ShiggyXposed.xposed.modules.LogBox

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.LinearLayout
import ShiggyXposed.xposed.Utils.Log

object LogBoxDialogs {

    var currentMenuContainer: LinearLayout? = null

    fun createDialog(context: Context, container: LinearLayout): AlertDialog {
        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()

        dialog.window?.setBackgroundDrawable(
            LogBoxUtils.createM3Background(context, Color.TRANSPARENT, 28f)
        )

        constrainContainerWidth(context, container)
        return dialog
    }

    fun constrainContainerWidth(context: Context, container: LinearLayout) {
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

    fun setDialogWindowWidth(dialog: AlertDialog, context: Context) {
        try {
            val screenW = context.resources.displayMetrics.widthPixels
            val maxByPercent = (screenW * 0.9).toInt()
            val maxByDp = LogBoxUtils.dpToPx(context, 420)
            val targetW = if (maxByPercent < maxByDp) maxByPercent else maxByDp

            dialog.window?.setLayout(targetW, ViewGroup.LayoutParams.WRAP_CONTENT)

            try {
                currentMenuContainer?.let { container ->
                    val lp = container.layoutParams ?: LinearLayout.LayoutParams(
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
}
