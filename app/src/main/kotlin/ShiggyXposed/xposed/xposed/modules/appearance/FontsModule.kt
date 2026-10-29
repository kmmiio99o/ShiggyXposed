package ShiggyXposed.xposed.modules.appearance

import android.content.res.AssetManager
import android.graphics.Typeface
import android.graphics.Typeface.CustomFallbackBuilder
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ShiggyXposed.xposed.Constants
import ShiggyXposed.xposed.Module
import ShiggyXposed.xposed.Utils.Companion.JSON
import ShiggyXposed.xposed.Utils.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

@Serializable
data class FontDefinition(
    val name: String? = null,
    val description: String? = null,
    val spec: Int? = null,
    val main: Map<String, String>,
)

object FontsModule : Module() {
    private val EXTENSIONS = arrayOf("", "_bold", "_italic", "_bold_italic")
    private val FILE_EXTENSIONS = arrayOf(".ttf", ".otf")
    private const val FONTS_ASSET_PATH = "fonts/"

    private val FONT_MANAGER_CLASSES = arrayOf(
        "com.facebook.react.views.text.ReactFontManager\$Companion",
        "com.facebook.react.common.assets.ReactFontManager\$Companion",
    )

    private var fontsDir: File? = null
    private var fontsDownloadsDir: File? = null
    private var fontsAbsPath: String? = null

    @Deprecated("This will be removed in future versions. Payloads can be replaced via synchronous bridge methods.")
    override fun buildPayload(builder: JsonObjectBuilder) {
        builder.apply {
            put("fontPatch", 2)
        }
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        val replacement = object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Typeface? {
                val fontFamilyName: String = param.args[0].toString()
                val style = when (val styleArg = param.args[1]) {
                    is Int -> styleArg
                    else -> styleFromFontStyle(styleArg)
                }
                val assetManager: AssetManager = param.args[2] as AssetManager
                return createAssetTypeface(fontFamilyName, style, assetManager)
            }
        }

        // React Native moved ReactFontManager between releases, so try every known package.
        // Never let this throw: an uncaught error here aborts the whole module chain.
        val hooked = FONT_MANAGER_CLASSES.any { className ->
            runCatching {
                XposedHelpers.findAndHookMethod(
                    className,
                    classLoader,
                    "createAssetTypeface",
                    String::class.java,
                    Int::class.java,
                    "android.content.res.AssetManager",
                    replacement
                )
            }.onFailure { Log.e("Failed to hook createAssetTypeface on $className", it) }
                .isSuccess
        }

        // Discord 352+ ships an obfuscated RN font manager. Its public entry point is
        // il.c.a(String fontFamilyName, il.b fontStyle, AssetManager).
        val newFontManager = classLoader.safeLoadClass("il.c")
        val newFontStyle = classLoader.safeLoadClass("il.b")
        val newHooked = if (newFontManager != null && newFontStyle != null) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    newFontManager,
                    "a",
                    String::class.java,
                    newFontStyle,
                    AssetManager::class.java,
                    replacement
                )
            }.onFailure { Log.e("Failed to hook il.c.a", it) }
                .isSuccess
        } else false

        if (!hooked && !newHooked) {
            Log.e("Failed to hook createAssetTypeface on all known font manager classes")
        }

        val fontDefFile = File(appInfo.dataDir, "${Constants.FILES_DIR}/fonts.json").apply { asFile() }
        if (!fontDefFile.exists()) return@with

        val fontDef = try {
            JSON.decodeFromString<FontDefinition>(fontDefFile.readText())
        } catch (_: Throwable) {
            return@with
        }

        val fontName = fontDef.name ?: return@with
        val downloadsDir = File(appInfo.dataDir, "${Constants.FILES_DIR}/downloads/fonts").apply { asDir() }
        val setDir = File(downloadsDir, fontName).apply { asDir() }
        fontsDownloadsDir = downloadsDir
        fontsDir = setDir
        fontsAbsPath = setDir.absolutePath + "/"

        setDir.listFiles()?.forEach { file ->
            val fileName = file.name
            if (!fileName.startsWith(".")) {
                val fontName = fileName.split('.')[0]
                if (fontDef.main.keys.none { it == fontName }) {
                    Log.i("Deleting font file: $fileName")
                    file.delete()
                }
            }
        }

        // These files should be downloaded by the JS side, but oh well
        CoroutineScope(Dispatchers.IO).launch {
            fontDef.main.keys.map { name ->
                async {
                    val url = fontDef.main.getValue(name)
                    try {
                        Log.i("Downloading $name from $url")
                        val file =
                            File(downloadsDir, "$fontName/$name${FILE_EXTENSIONS.first { url.endsWith(it) }}")
                                .apply { asFile() }
                        if (file.exists()) return@async

                        val client = HttpClient(CIO) {
                            install(UserAgent) { agent = Constants.USER_AGENT }
                        }

                        val response: HttpResponse = client.get(url)
                        if (response.status == HttpStatusCode.OK) file.writeBytes(response.body())

                        return@async
                    } catch (e: Throwable) {
                        Log.e("Failed to download fonts ($name from $url)", e)
                    }
                }
            }.awaitAll()
        }
    }

    private fun createAssetTypefaceWithFallbacks(
        fontFamilyNames: Array<String>, style: Int, assetManager: AssetManager
    ): Typeface? {
        val fontFamilies: MutableList<FontFamily> = ArrayList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Iterate over the list of fontFamilyNames, constructing new FontFamily objects
            // for use in the CustomFallbackBuilder below.
            for (fontFamilyName in fontFamilyNames) {
                try {
                    for (fileExtension in FILE_EXTENSIONS) {
                        val file = customFontFile(fontFamilyName, fileExtension) ?: continue
                        val font = Font.Builder(file).build()
                        val family = FontFamily.Builder(font).build()
                        fontFamilies.add(family)
                    }
                } catch (_: Throwable) {
                }

                for (fontRootPath in arrayOf(fontsAbsPath, FONTS_ASSET_PATH).filterNotNull()) {
                    for (fileExtension in FILE_EXTENSIONS) {
                        val fileName =
                            StringBuilder().append(fontRootPath).append(fontFamilyName).append(fileExtension).toString()
                        try {
                            val builder = if (fileName[0] == '/') Font.Builder(File(fileName)) else Font.Builder(
                                assetManager, fileName
                            )
                            val font = builder.build()
                            val family = FontFamily.Builder(font).build()
                            fontFamilies.add(family)
                        } catch (_: RuntimeException) {
                            // If the typeface asset does not exist, try another extension.
                            continue
                        } catch (_: IOException) {
                            // If the font asset does not exist, try another extension.
                            continue
                        }
                    }
                }
            }

            // If there's some problem constructing fonts, fall back to the default behavior.
            if (fontFamilies.isEmpty()) return createAssetTypeface(fontFamilyNames[0], style, assetManager)

            val fallbackBuilder = CustomFallbackBuilder(fontFamilies[0])
            for (i in 1 until fontFamilies.size) {
                fallbackBuilder.addCustomFallback(fontFamilies[i])
            }
            return fallbackBuilder.build()
        }
        return null
    }

    private fun createAssetTypeface(
        fontFamilyName: String, style: Int, assetManager: AssetManager
    ): Typeface? {
        // This logic attempts to safely check if the frontend code is attempting to use
        // fallback fonts, and if it is, to use the fallback typeface creation logic.
        var fontFamilyName: String = fontFamilyName
        val fontFamilyNames = fontFamilyName.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (i in fontFamilyNames.indices) {
            fontFamilyNames[i] = fontFamilyNames[i].trim()
        }

        // If there are multiple font family names:
        //   For newer versions of Android, construct a Typeface with fallbacks
        //   For older versions of Android, ignore all the fallbacks and just use the first font family
        if (fontFamilyNames.size > 1) {
            fontFamilyName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return createAssetTypefaceWithFallbacks(fontFamilyNames, style, assetManager)
            } else {
                fontFamilyNames[0]
            }
        }

        val extension = EXTENSIONS.getOrElse(style) { "" }

        try {
            for (fileExtension in FILE_EXTENSIONS) {
                val file = customFontFile(fontFamilyName, fileExtension) ?: continue
                if (!file.exists()) continue
                return Typeface.createFromFile(file.absolutePath)
            }
        } catch (_: Throwable) {
        }

        // Lastly, after all those checks above, this is the original RN logic for
        // getting the typeface.
        for (fontRootPath in arrayOf(fontsAbsPath, FONTS_ASSET_PATH).filterNotNull()) {
            for (fileExtension in FILE_EXTENSIONS) {
                val fileName =
                    StringBuilder().append(fontRootPath).append(fontFamilyName).append(extension).append(fileExtension)
                        .toString()

                return try {
                    if (fileName[0] == '/') Typeface.createFromFile(fileName)
                    else Typeface.createFromAsset(assetManager, fileName)
                } catch (_: RuntimeException) {
                    // If the typeface asset does not exist, try another extension.
                    continue
                }
            }
        }

        return Typeface.create(fontFamilyName, style)
    }

    private fun customFontFile(fontFamilyName: String, fileExtension: String): File? {
        val parts = fontFamilyName.split(":")
        if (parts.size != 2) return null
        val downloadsDir = fontsDownloadsDir ?: return null
        return File(downloadsDir, "${parts[0]}/${parts[1]}$fileExtension")
    }

    /**
     * Discord 352+ passes a font-style holder object instead of the old RN style enum.
     * It exposes a weight int and an italic boolean; translate them back to RN's style index.
     */
    private fun styleFromFontStyle(fontStyle: Any?): Int {
        if (fontStyle == null) return 0
        return runCatching {
            val fields = fontStyle.javaClass.declaredFields
            val italic = fields.firstOrNull { it.type == Boolean::class.javaPrimitiveType }
                ?.apply { isAccessible = true }
                ?.getBoolean(fontStyle) ?: false
            val weight = fields.firstOrNull { it.type == Int::class.javaPrimitiveType }
                ?.apply { isAccessible = true }
                ?.getInt(fontStyle) ?: 400

            if (weight >= 700) {
                if (italic) 3 else 1
            } else {
                if (italic) 2 else 0
            }
        }.getOrDefault(0)
    }
}
