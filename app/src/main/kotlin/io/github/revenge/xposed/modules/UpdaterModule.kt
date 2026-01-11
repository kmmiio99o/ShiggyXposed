package io.github.revenge.xposed.modules

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.AtomicFile
import android.widget.Toast
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.rushii.libunbound.LibUnbound
import io.github.revenge.xposed.Constants
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils
import io.github.revenge.xposed.Utils.Companion.JSON
import io.github.revenge.xposed.Utils.Companion.reloadApp
import io.github.revenge.xposed.Utils.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.lang.ref.WeakReference

@Serializable
data class CustomLoadUrl(val enabled: Boolean = false, val url: String = "")

@Serializable
data class LoaderConfig(val customLoadUrl: CustomLoadUrl = CustomLoadUrl(), val disableInjection: Boolean = false)

@Serializable
data class EndpointInfo(val paths: ArrayList<String>, val hash: String? = null, val version: String)

object UpdaterModule : Module() {
    private lateinit var config: LoaderConfig
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastActivity: WeakReference<Activity>? = null

    private lateinit var cacheDir: File
    private lateinit var bundle: File
    private lateinit var etag: File

    private const val TIMEOUT_STRICT = 15000L
    private const val MIN_BYTECODE_SIZE = 512
    private const val ETAG_FILE = "etag.txt"
    private const val CONFIG_FILE = "loader.json"

    private const val DEFAULT_BASE_URL = "https://codeberg.org/cocobo1/Kettu/raw/branch/dist/"
    private const val DEFAULT_BUNDLE_NAME = "kettu.min.js"

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        cacheDir = File(appInfo.dataDir, Constants.CACHE_DIR).apply { mkdirs() }
        val filesDir = File(appInfo.dataDir, Constants.FILES_DIR).apply { mkdirs() }

        bundle = File(cacheDir, Constants.MAIN_SCRIPT_FILE)
        etag = File(cacheDir, ETAG_FILE)

        val configFile = File(filesDir, CONFIG_FILE)
        config = runCatching {
            if (configFile.exists()) JSON.decodeFromString<LoaderConfig>(configFile.readText()) else LoaderConfig()
        }.getOrDefault(LoaderConfig())
    }

    fun downloadScript(activity: Activity? = null): Job = scope.launch {
        try {
            HttpClient(CIO) {
                expectSuccess = false
                install(HttpTimeout) {
                    requestTimeoutMillis = TIMEOUT_STRICT
                    connectTimeoutMillis = 5000L
                    socketTimeoutMillis = TIMEOUT_STRICT
                }
                install(UserAgent) { agent = Constants.USER_AGENT }
                install(HttpRedirect) { checkHttpMethod = false }
            }.use { client ->
                val targetUrl = resolveTargetUrl(client)
                Log.i("Fetching bundle: $targetUrl")

                val response: HttpResponse = client.get(targetUrl) {
                    headers {
                        if (etag.exists() && bundle.exists()) {
                            append(HttpHeaders.IfNoneMatch, etag.readText())
                        }
                    }
                }

                when (response.status) {
                    HttpStatusCode.OK -> {
                        val bytes: ByteArray = response.body()
                        
                        if (bytes.size < MIN_BYTECODE_SIZE) {
                            throw Exception("Payload too small (${bytes.size} bytes). Possible corrupt build.")
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

                        response.headers[HttpHeaders.ETag]?.let { etag.writeText(it) } ?: etag.delete()
                        Log.i("Bundle updated: ${bytes.size} bytes")

                        if (activity != null) {
                            withContext(Dispatchers.Main) {
                                AlertDialog.Builder(activity)
                                    .setTitle("Update Successful")
                                    .setMessage("Reload required.")
                                    .setPositiveButton("Reload") { _, _ -> reloadApp() }
                                    .setCancelable(false)
                                    .show()
                            }
                        }
                    }
                    HttpStatusCode.NotModified -> Log.i("Bundle is up to date (304)")
                    else -> throw ResponseException(response, "HTTP ${response.status}")
                }
            }
        } catch (e: Throwable) {
            Log.e("Updater Error", e)
            if (activity != null) withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun resolveTargetUrl(client: HttpClient): String {
        if (config.customLoadUrl.enabled && config.customLoadUrl.url.isNotEmpty()) {
            return config.customLoadUrl.url
        }

        return try {
            val infoResponse = client.get("${DEFAULT_BASE_URL}info.json")
            if (infoResponse.status == HttpStatusCode.OK) {
                val info = JSON.decodeFromString<EndpointInfo>(infoResponse.bodyAsText())
                val hermesVersion = withTimeoutOrNull(2000L) {
                    runCatching { LibUnbound.getHermesRuntimeBytecodeVersion() }.getOrNull()
                } ?: 96
                
                val hbcName = "kettu.$hermesVersion.hbc"
                when {
                    info.paths.contains(hbcName) -> DEFAULT_BASE_URL + hbcName
                    info.paths.contains("kettu.min.js") -> DEFAULT_BASE_URL + "kettu.min.js"
                    else -> DEFAULT_BASE_URL + DEFAULT_BUNDLE_NAME
                }
            } else DEFAULT_BASE_URL + DEFAULT_BUNDLE_NAME
        } catch (e: Exception) {
            DEFAULT_BASE_URL + DEFAULT_BUNDLE_NAME
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