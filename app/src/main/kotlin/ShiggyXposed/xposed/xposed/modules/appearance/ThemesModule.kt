package ShiggyXposed.xposed.modules.appearance

import android.content.Context
import android.content.res.Resources
import android.view.View
import androidx.core.graphics.toColorInt
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ShiggyXposed.xposed.Constants
import ShiggyXposed.xposed.Module
import ShiggyXposed.xposed.Utils.Companion.JSON
import ShiggyXposed.xposed.Utils.Log
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File

@Serializable
data class Author(
    val name: String, val id: String? = null
)

@Serializable
data class ThemeData(
    val name: String,
    val description: String? = null,
    val authors: List<Author>? = null,
    val spec: Int,
    val semanticColors: Map<String, List<String>>? = null,
    val rawColors: Map<String, String>? = null
)

@Serializable
data class Theme(
    val id: String, val selected: Boolean, val data: ThemeData
)

object ThemesModule : Module() {
    private lateinit var param: XC_LoadPackage.LoadPackageParam

    private var theme: Theme? = null
    private val rawColorMap = mutableMapOf<String, Int>()

    private const val THEME_FILE = "current-theme.json"


    @ExperimentalSerializationApi
    override fun buildPayload(builder: JsonObjectBuilder) {
        builder.apply {
            put("hasThemeSupport", true)
            if (theme != null) put("storedTheme", JSON.encodeToJsonElement<Theme>(theme!!))
            else put("storedTheme", null)
        }
    }

    private fun String.fromScreamingSnakeToCamelCase() =
        this.split("_").joinToString("") { it -> it.lowercase().replaceFirstChar { it.uppercase() } }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {
        param = packageParam
        theme = getTheme()
        hookTheme()
    }

    private fun File.isValidThemeFile(): Boolean {
        if (!this.exists()) return false

        val text = this.readText()
        return text.isNotBlank() && text != "{}" && text != "null"
    }

    private fun getTheme(): Theme? {
        val themeFile = File(param.appInfo.dataDir, "${Constants.FILES_DIR}/${THEME_FILE}").apply { asFile() }
        if (!themeFile.isValidThemeFile()) return null

        return try {
            val themeText = themeFile.readText()
            JSON.decodeFromString<Theme>(themeText)
        } catch (_: Exception) {
            null
        }
    }

    fun hookTheme() {
        val theme = this.theme ?: return

        // Apply rawColors
        theme.data.rawColors?.forEach { (key, value) ->
            rawColorMap[key.lowercase()] = hexStringToColorInt(value)
        }

        // Discord 352+ moved the theme palette into ThemeModule, while older builds used
        // DarkerTheme/DarkTheme and LightTheme. Keep every known target so an obfuscated
        // or missing class only disables the semantic hook for that version.
        val darkThemes = listOf(
            "com.discord.theme.ThemeModule\$Dark",
            "com.discord.theme.DarkerTheme",
            "com.discord.theme.DarkTheme",
        ).mapNotNull { param.classLoader.safeLoadClass(it) }
        val lightThemes = listOf(
            "com.discord.theme.ThemeModule\$Light",
            "com.discord.theme.LightTheme",
        ).mapNotNull { param.classLoader.safeLoadClass(it) }

        if (darkThemes.isEmpty() || lightThemes.isEmpty()) {
            Log.w("Discord theme palette classes not found, skipping semantic color hooks")
        }

        // Apply semanticColors
        theme.data.semanticColors?.forEach { (key, value) ->
            // TEXT_NORMAL -> getTextNormal
            val methodName = "get${key.fromScreamingSnakeToCamelCase()}"
            value.forEachIndexed { index, v ->
                val targets = when (index) {
                    0 -> darkThemes
                    1 -> lightThemes
                    else -> emptyList()
                }
                targets.forEach { hookThemeMethod(it, methodName, hexStringToColorInt(v)) }
            }
        }

        // If there's any rawColors value, hook the color getter
        if (theme.data.rawColors.isNullOrEmpty()) return

        val patch = MethodHookBuilder().run {
            before {
                val resources = when {
                    args[0] is Context -> (args[0] as Context).resources
                    args[0] is Resources -> args[0] as Resources
                    args.size > 1 && args[1] is Resources -> args[1] as Resources
                    args.size > 2 && args[2] is Resources -> args[2] as Resources
                    args.size > 1 && args[1] is View -> (args[1] as View).context.resources
                    else -> null
                }
                val colorId = when {
                    args[0] is Int -> args[0] as Int
                    args.size > 1 && args[1] is Int -> args[1] as Int
                    else -> null
                }

                if (resources != null && colorId != null) {
                    val name = runCatching { resources.getResourceEntryName(colorId) }.getOrNull()
                    if (name != null) rawColorMap[name]?.let { result = it }
                }
            }

            build()
        }

        // Legacy color resolver (through Discord 351).
        param.classLoader.safeLoadClass("com.discord.theme.utils.ColorUtilsKt")?.let { themeManager ->
            listOf(
                runCatching {
                    themeManager.getDeclaredMethod(
                        "getColorCompat",
                        Resources::class.java,
                        Int::class.javaPrimitiveType,
                        Resources.Theme::class.java,
                    )
                }.getOrNull(),
                runCatching {
                    themeManager.getDeclaredMethod(
                        "getColorCompat", Context::class.java, Int::class.javaPrimitiveType
                    )
                }.getOrNull()
            ).filterNotNull().forEach { it.hook(patch) }
        }

        // Discord 352+ color resolver. vh.c.c(int, Theme, Resources) and vh.c.e(Context, int)
        // are the new getColorCompat equivalents; vh.c.d(int, View) is also widely used.
        param.classLoader.safeLoadClass("vh.c")?.let { colorUtils ->
            listOf(
                runCatching {
                    colorUtils.getDeclaredMethod(
                        "c",
                        Int::class.javaPrimitiveType,
                        Resources.Theme::class.java,
                        Resources::class.java,
                    )
                }.getOrNull(),
                runCatching {
                    colorUtils.getDeclaredMethod(
                        "e", Context::class.java, Int::class.javaPrimitiveType
                    )
                }.getOrNull(),
                runCatching {
                    colorUtils.getDeclaredMethod(
                        "d", Int::class.javaPrimitiveType, View::class.java
                    )
                }.getOrNull(),
            ).filterNotNull().forEach { it.hook(patch) }
        }
    }

    // Parse HEX colour string to INT. Takes "#RRGGBBAA" or "#RRGGBB"
    private fun hexStringToColorInt(hexString: String): Int {
        return if (hexString.length == 9) {
            // Rearrange RRGGBBAA -> AARRGGBB so parseColor() is happy
            val alpha = hexString.substring(7, 9)
            val rrggbb = hexString.substring(1, 7)
            "#$alpha$rrggbb".toColorInt()
        } else hexString.toColorInt()
    }

    private fun hookThemeMethod(themeClass: Class<*>, methodName: String, themeValue: Int) {
        try {
            themeClass.getDeclaredMethod(methodName).let { method ->
                method.hook {
                    before {
                        result = themeValue
                    }
                }
            }
        } catch (_: NoSuchMethodException) {
        }
    }
}
