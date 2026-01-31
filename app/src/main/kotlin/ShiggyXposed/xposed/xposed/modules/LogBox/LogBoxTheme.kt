package ShiggyXposed.xposed.modules.LogBox

import android.content.Context
import android.graphics.Color
import org.json.JSONObject
import java.io.File
import ShiggyXposed.xposed.Utils.Log

object LogBoxTheme {

    fun isDarkMode(context: Context): Boolean {
        try {
            val settingsFile = File(context.filesDir, "logbox/LOGBOX_SETTINGS")
            if (settingsFile.exists()) {
                val json = JSONObject(settingsFile.readText())
                val mode = json.optString("appearanceMode", "")
                if (mode == "light") return false
                if (mode == "dark") return true
            }
        } catch (e: Exception) {
            Log.e("Error reading LogBox appearance settings: ${e.message}")
        }

        return (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun getM3Colors(context: Context): M3Colors {

        val isDark = isDarkMode(context)
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

        var flavor = "mocha"

        try {
            val themeFile = File(context.filesDir, "logbox/LOGBOX_THEMES")
            if (themeFile.exists()) {
                val json = JSONObject(themeFile.readText())
                val f = json.optString("menuFlavor", json.optString("flavor", json.optString("id", ""))).lowercase()
                if (f.isNotEmpty()) flavor = f
            }

            if (flavor.isNotEmpty()) {
                val pair = LogBoxConstants.FLAVOR_COLORS[flavor]
                if (pair != null) {
                    val primary = Color.parseColor(pair.first)
                    val primaryContainer = Color.parseColor(pair.second)
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
        } catch (e: Exception) {
            Log.e("Error applying LogBox flavor: ${e.message}")
        }

        return base
    }
}
