package ShiggyXposed.xposed.modules.bridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import ShiggyXposed.xposed.Module
import ShiggyXposed.xposed.Utils
import ShiggyXposed.xposed.Utils.Companion.reloadApp
import java.io.File

object AdditionalBridgeMethodsModule : Module() {
    override fun onContext(context: Context) = with(context) {
        BridgeModule.registerMethod("Shiggy.fs.getConstants") {
            mapOf(
                "data" to dataDir.absolutePath,
                "files" to filesDir.absolutePath,
                "cache" to cacheDir.absolutePath,
            )
        }

        BridgeModule.registerMethod("Shiggy.fs.delete") {
            val (path) = it
            File(path as String).run {
                if (this.isDirectory) this.deleteRecursively()
                else this.delete()
            }
        }

        BridgeModule.registerMethod("Shiggy.fs.exists") {
            val (path) = it
            File(path as String).exists()
        }

        BridgeModule.registerMethod("Shiggy.fs.read") { it ->
            val (path) = it
            val file = File(path as String).apply { openFileGuarded() }

            file.bufferedReader().use { it.readText() }
        }

        BridgeModule.registerMethod("Shiggy.fs.write") {
            val (path, contents) = it
            val file = File(path as String).apply { openFileGuarded() }

            file.writeText(contents as String)
        }

        BridgeModule.registerMethod("Shiggy.app.reload") {
            reloadApp()
            null
        }
    }

    override fun onActivity(activity: Activity) = with(activity) {
        BridgeModule.registerMethod("Shiggy.alertError") {
            val (error, version) = it
            val app = getAppInfo()
            val errorString = "$error"

            val clipboard = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Stack Trace", errorString)

            AlertDialog.Builder(this)
                .setTitle("ShiggyCord Error")
                .setMessage(
                    """
                    ShiggyCord: $version
                    ${app.name}: ${app.version} (${app.versionCode})
                    Device: ${Build.MANUFACTURER} ${Build.MODEL}


                """.trimIndent() + errorString
                )
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                .setNeutralButton("Copy") { dialog, _ ->
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(applicationContext, "Copied stack trace", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .show()

            null
        }

        BridgeModule.registerMethod("Shiggy.showRecoveryAlert") {
            Utils.showRecoveryAlert(this)
        }
    }

    private fun File.openFileGuarded() {
        if (!this.exists()) throw Error("Path does not exist: $path")
        if (!this.isFile) throw Error("Path is not a file: $path")
    }
}
