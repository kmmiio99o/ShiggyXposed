package ShiggyXposed.xposed.modules

import android.app.Activity
import android.content.Context
import android.util.AtomicFile
import android.widget.Toast
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.rushii.libunbound.LibUnbound
import ShiggyXposed.xposed.Constants
import ShiggyXposed.xposed.Module
import ShiggyXposed.xposed.Utils
import ShiggyXposed.xposed.Utils.Companion.JSON

import ShiggyXposed.xposed.Utils.Log
import ShiggyXposed.xposed.modules.bridge.BridgeModule
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

@Serializable
data class CustomLoadUrl(val enabled: Boolean = false, val url: String = "")

@Serializable
data class LoaderConfig(val customLoadUrl: CustomLoadUrl = CustomLoadUrl(), val disableInjection: Boolean = false)

@Serializable
data class EndpointInfo(val paths: ArrayList<String>, val hash: String? = null, val version: String)

object UpdaterModule : Module() {
    private lateinit var config: LoaderConfig
    val isCustomUrlEnabled: Boolean
        get() = config.customLoadUrl.enabled
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastActivity: WeakReference<Activity>? = null

    private lateinit var cacheDir: File
    private lateinit var bundle: File
    private lateinit var etag: File

    private const val TIMEOUT_STRICT = 15000L
    private const val MIN_BYTECODE_SIZE = 512
    private const val ETAG_FILE = "etag.txt"
    private const val CONFIG_FILE = "loader.json"

    private const val DEFAULT_BASE_URL = "https://github.com/kmmiio99o/ShiggyCord/releases/latest/download/"
    private const val DEFAULT_BUNDLE_NAME = "shiggycord.min.js"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_STRICT, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT_STRICT, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_STRICT, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        cacheDir = File(appInfo.dataDir, Constants.CACHE_DIR).apply { mkdirs() }
        val filesDir = File(appInfo.dataDir, Constants.FILES_DIR).apply { mkdirs() }

        bundle = File(cacheDir, Constants.MAIN_SCRIPT_FILE)
        etag = File(cacheDir, ETAG_FILE)

        val configFile = File(filesDir, CONFIG_FILE)
        config = runCatching {
            if (configFile.exists()) JSON.decodeFromString<LoaderConfig>(configFile.readText()) else LoaderConfig()
        }.getOrDefault(LoaderConfig())

        BridgeModule.registerMethod("revenge.updater.clear") {
            if (bundle.exists()) bundle.delete()
            if (etag.exists()) etag.delete()
            null
        }
    }

    fun downloadScript(activity: Activity? = null, showUpdateDialog: Boolean = true): Job = scope.launch {
        try {
            val targetUrl = resolveTargetUrl()
            Log.i("Fetching bundle: $targetUrl")

            val builder = Request.Builder().url(targetUrl)
                .header("User-Agent", Constants.USER_AGENT)

            if (etag.exists() && bundle.exists()) {
                builder.header("If-None-Match", etag.readText())
            }

            client.newCall(builder.build()).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val bytes = response.body?.bytes()
                            ?: throw IOException("Empty response body")

                        if (bytes.size < MIN_BYTECODE_SIZE) {
                            throw IOException("Payload too small (${bytes.size} bytes). Possible corrupt build.")
                        }

                        AtomicFile(bundle).apply {
                            val stream = startWrite()
                            try {
                                stream.write(bytes)
                                finishWrite(stream)
                            } catch (e: Exception) {
                                failWrite(stream)
                                throw e
                            }
                        }

                        response.header("ETag")?.let { etag.writeText(it) } ?: etag.delete()
                        Log.i("Bundle updated: ${bytes.size} bytes")
                    }

                    304 -> Log.i("Bundle is up to date (304)")
                    else -> throw IOException("HTTP ${response.code}")
                }
            }
        } catch (e: Throwable) {
            Log.e("Updater Error", e)
        }
    }

    private suspend fun resolveTargetUrl(): String {
        if (config.customLoadUrl.enabled && config.customLoadUrl.url.isNotEmpty()) {
            return config.customLoadUrl.url
        }

        return tryResolveUrl(DEFAULT_BASE_URL)
            ?: DEFAULT_BASE_URL + DEFAULT_BUNDLE_NAME
    }

    private suspend fun tryResolveUrl(baseUrl: String): String? {
        return try {
            val request = Request.Builder().url("${baseUrl}info.json")
                .header("User-Agent", Constants.USER_AGENT)
                .build()

            val info = client.newCall(request).execute().use { response ->
                if (response.code != 200) return@use null
                JSON.decodeFromString<EndpointInfo>(response.body?.string() ?: return@use null)
            } ?: return null

            val hermesVersion = withTimeoutOrNull(2000L) {
                runCatching { LibUnbound.getHermesRuntimeBytecodeVersion() }.getOrNull()
            } ?: 96

            val hbcName = "shiggycord.$hermesVersion.hbc"
            when {
                info.paths.contains(hbcName) -> baseUrl + hbcName
                info.paths.contains("shiggycord.min.js") -> baseUrl + "shiggycord.min.js"
                else -> baseUrl + DEFAULT_BUNDLE_NAME
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onActivity(activity: Activity) {
        lastActivity = WeakReference(activity)
    }

    fun setDisableInjection(context: Context, disabled: Boolean) {
        val filesDir = File(context.dataDir, Constants.FILES_DIR).apply { mkdirs() }
        val configFile = File(filesDir, CONFIG_FILE)
        val newCfg = config.copy(disableInjection = disabled)
        configFile.writeText(JSON.encodeToString(newCfg))
        config = newCfg
        Toast.makeText(context, "Injection ${if (disabled) "disabled" else "enabled"}", Toast.LENGTH_SHORT).show()
    }

    fun isInjectionDisabled(context: Context? = null): Boolean {
        return if (::config.isInitialized) config.disableInjection else false
    }
}
