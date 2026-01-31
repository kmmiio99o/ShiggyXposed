package ShiggyXposed.xposed.modules.LogBox

import android.content.Context
import android.util.TypedValue
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.content.res.ColorStateList
import android.view.View
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import ShiggyXposed.xposed.Constants
import ShiggyXposed.xposed.Utils.Log

object LogBoxUtils {

    fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    fun createM3Background(context: Context, color: Int, cornerRadius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            setCornerRadius(dpToPx(context, cornerRadius.toInt()).toFloat())
        }
    }

    fun getPyoncordDirectory(context: Context): File {
        val dir = File(context.filesDir, "pyoncord")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun showM3Toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun getAppearanceMode(context: Context): String {
        return try {
            val settingsFile = File(context.filesDir, "logbox/LOGBOX_SETTINGS")
            if (!settingsFile.exists()) return "system"
            val json = JSONObject(settingsFile.readText())
            json.optString("appearanceMode", "system")
        } catch (e: Exception) {
            Log.e("Error reading appearance mode: ${e.message}")
            "system"
        }
    }

    fun setAppearanceMode(context: Context, mode: String) {
        try {
            val settingsFile = File(context.filesDir, "logbox/LOGBOX_SETTINGS")
            settingsFile.parentFile?.mkdirs()
            val settings = if (settingsFile.exists()) JSONObject(settingsFile.readText()) else JSONObject()
            settings.put("appearanceMode", mode)
            settingsFile.writeText(settings.toString())
        } catch (e: Exception) {
            LogBoxActions.showError(context, "Failed to set appearance", e.message)
        }
    }

    fun saveMenuFlavor(context: Context, flavor: String) {
        try {
            val themeFile = File(context.filesDir, "logbox/LOGBOX_THEMES")
            themeFile.parentFile?.mkdirs()

            val themeJson = JSONObject().apply {
                put("menuFlavor", flavor)
            }

            themeFile.writeText(themeJson.toString())
        } catch (e: Exception) {
            Log.e("Error saving menu flavor: ${e.message}")
            LogBoxActions.showError(context, "Failed to save flavor", e.message)
        }
    }

    fun isBundleInjectionDisabled(context: Context): Boolean {
        return try {
            val cacheDir = File(context.dataDir, Constants.CACHE_DIR)
            val bundle = File(cacheDir, Constants.MAIN_SCRIPT_FILE)
            val disabled = File(cacheDir, "${Constants.MAIN_SCRIPT_FILE}.disabled")
            if (disabled.exists()) return true
            if (bundle.exists()) return false

            val settingsFile = File(context.filesDir, "logbox/LOGBOX_SETTINGS")
            if (!settingsFile.exists()) return false
            val json = JSONObject(settingsFile.readText())
            json.optBoolean("bundleInjectionDisabled", false)
        } catch (e: Exception) {
            Log.e("Error reading injection setting: ${e.message}")
            false
        }
    }

    fun isSafeModeEnabled(context: Context): Boolean {
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
}
