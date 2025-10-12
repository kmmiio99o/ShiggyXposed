package ShiggyXposed.xposed.modules

import ShiggyXposed.xposed.Constants
import ShiggyXposed.xposed.Module
import ShiggyXposed.xposed.Utils
import ShiggyXposed.xposed.Utils.Companion.reloadApp
import ShiggyXposed.xposed.Utils.Log
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import kotlinx.serialization.json.*

/**
 * Replacement for the old recovery/dev dialog. Shows a dark-themed dialog on the main thread with:
 * - Safe Mode toggle (writes/removes preloads/00_rv_safe_mode.js)
 * - Load from custom URL toggle + URL input (persists to files/pyoncord/loader.json)
 * - Save & Reload, Delete bundle, Close actions
 *
 * Safe Mode toggle now behaves like the React snippet: toggles immediately (no reload) and opens a
 * confirmation modal asking whether to reload now or later. The confirmation modal uses a custom
 * in-dialog layout so button colors and text can be controlled for dark styling.
 */
class LogBoxModule : Module() {
    lateinit var packageParam: XC_LoadPackage.LoadPackageParam
    lateinit var bridgeDevSupportManagerClass: Class<*>

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) =
            with(packageParam) {
                this@LogBoxModule.packageParam = packageParam

                try {
                    val dcdReactNativeHostClass =
                            classLoader.loadClass("com.discord.bridge.DCDReactNativeHost")
                    bridgeDevSupportManagerClass =
                            classLoader.loadClass(
                                    "com.facebook.react.devsupport.BridgeDevSupportManager"
                            )

                    val getUseDeveloperSupportMethod =
                            dcdReactNativeHostClass.methods.first {
                                it.name == "getUseDeveloperSupport"
                            }
                    val handleReloadJSMethod =
                            bridgeDevSupportManagerClass.methods.first {
                                it.name == "handleReloadJS"
                            }
                    val showDevOptionsDialogMethod =
                            bridgeDevSupportManagerClass.methods.first {
                                it.name == "showDevOptionsDialog"
                            }
                    val mReactInstanceDevHelperField =
                            XposedHelpers.findField(
                                    bridgeDevSupportManagerClass,
                                    "mReactInstanceDevHelper"
                            )

                    // Enable developer support (so shake/dev menu becomes available)
                    XposedBridge.hookMethod(
                            getUseDeveloperSupportMethod,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    param.result = true
                                }
                            }
                    )

                    // Replace reload with app restart to avoid remote dev server dependency
                    XposedBridge.hookMethod(
                            handleReloadJSMethod,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    reloadApp()
                                    param.result = null
                                }
                            }
                    )

                    // Intercept showDevOptionsDialog and replace with our dialog
                    XposedBridge.hookMethod(
                            showDevOptionsDialogMethod,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    try {
                                        val mReactInstanceDevHelper =
                                                mReactInstanceDevHelperField.get(param.thisObject)
                                        val getCurrentActivityMethod =
                                                mReactInstanceDevHelper.javaClass.methods.first {
                                                    it.name == "getCurrentActivity"
                                                }
                                        val context =
                                                getCurrentActivityMethod.invoke(
                                                        mReactInstanceDevHelper
                                                ) as
                                                        Context

                                        showRecoveryAlert(context)
                                    } catch (ex: Exception) {
                                        Log.e(
                                                "Failed to show dev options dialog: ${ex.message}",
                                                ex
                                        )
                                        XposedBridge.log(
                                                "ShiggyXposed: Failed to show dev options dialog: ${android.util.Log.getStackTraceString(ex)}"
                                        )
                                    }

                                    // Prevent original menu from showing
                                    param.result = null
                                }
                            }
                    )
                } catch (e: Exception) {
                    Log.e("Error in LogBoxModule.onLoad: ${e.message}", e)
                    XposedBridge.log(
                            "ShiggyXposed: LogBoxModule.onLoad error: ${android.util.Log.getStackTraceString(e)}"
                    )
                }

                return@with
            }

    private fun showRecoveryAlert(context: Context) {
        // Always perform UI operations on the main thread to avoid runtime exceptions
        Handler(Looper.getMainLooper()).post {
            try {
                // Wrap with a dark dialog theme from module resources (AppCompat-based).
                // Use the provided activity/context directly to avoid inflating module resources
                // into the host process (some hosts fail to resolve module resources). We set
                // colors programmatically on views instead of relying on theme attributes.
                val themed = context

                // Prepare paths
                val filesDir =
                        File(packageParam.appInfo.dataDir, Constants.FILES_DIR).apply { mkdirs() }
                val configFile = File(filesDir, "loader.json")
                val preloadsDir =
                        File(filesDir, HookScriptLoaderModule.PRELOADS_DIR).apply { mkdirs() }

                // Read existing loader.json (guarded)
                var customEnabled = false
                var customUrl = ""
                try {
                    if (configFile.exists()) {
                        val je = Utils.JSON.parseToJsonElement(configFile.readText())
                        val custom = je.jsonObject["customLoadUrl"]?.jsonObject
                        if (custom != null) {
                            customEnabled = custom["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                            customUrl = custom["url"]?.jsonPrimitive?.contentOrNull ?: ""
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("Failed to parse loader.json: ${t.message}", t)
                    XposedBridge.log(
                            "ShiggyXposed: loader.json parse failed: ${android.util.Log.getStackTraceString(t)}"
                    )
                }

                // Build the dialog UI programmatically to avoid inflating module resources which
                // can
                // fail when injected into some host apps.
                val padding = (themed.resources.displayMetrics.density * 16).toInt()
                val container =
                        LinearLayout(themed).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(padding, padding, padding, padding)
                            setBackgroundColor(0xFF121212.toInt())
                        }

                // Helper to create a labeled switch row
                fun addSwitchRow(labelText: String, checked: Boolean): Pair<TextView, Switch> {
                    val rowLp =
                            LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                    val row =
                            LinearLayout(themed).apply {
                                orientation = LinearLayout.HORIZONTAL
                                layoutParams = rowLp
                            }

                    val tvLp =
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    val tv =
                            TextView(themed).apply {
                                text = labelText
                                setTextColor(0xFFFFFFFF.toInt())
                                textSize = 15f
                                layoutParams = tvLp
                            }

                    val swLp =
                            LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                    val sw =
                            Switch(themed).apply {
                                isChecked = checked
                                layoutParams = swLp
                            }

                    row.addView(tv)
                    row.addView(sw)
                    container.addView(row)
                    return Pair(tv, sw)
                }

                // Create rows
                val safeRow = addSwitchRow("Safe mode (disable add-ons)", false)
                val safeSwitch = safeRow.second
                val customRow = addSwitchRow("Load from custom URL", false)
                val customSwitch = customRow.second

                // URL label + input
                val lblUrl =
                        TextView(themed).apply {
                            text = "Custom bundle URL"
                            setTextColor(0xFFFFFFFF.toInt())
                            textSize = 13f
                            val lp =
                                    LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                            lp.topMargin = (themed.resources.displayMetrics.density * 8).toInt()
                            layoutParams = lp
                        }
                val urlInput =
                        EditText(themed).apply {
                            hint = "https://example.com/bundle.js"
                            setTextColor(0xFFFFFFFF.toInt())
                            setHintTextColor(0xFFBDBDBD.toInt())
                            setBackgroundColor(0xFF1E1E1E.toInt())
                            val lp =
                                    LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                            lp.topMargin = (themed.resources.displayMetrics.density * 6).toInt()
                            layoutParams = lp
                            visibility = View.GONE
                        }
                container.addView(lblUrl)
                container.addView(urlInput)

                // Buttons row - keep primary actions (Delete / Close) on one row,
                // place the Save & Reload button on a separate row below so it's not squeezed.
                val buttonsRow =
                        LinearLayout(themed).apply {
                            orientation = LinearLayout.HORIZONTAL
                            val lp =
                                    LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                            lp.topMargin = (themed.resources.displayMetrics.density * 12).toInt()
                            layoutParams = lp
                        }

                fun createButton(text: String): Button {
                    return Button(themed).apply {
                        this.text = text
                        setAllCaps(false)
                        setTextColor(0xFFFFFFFF.toInt())
                        val bLp =
                                LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                        bLp.marginEnd = (themed.resources.displayMetrics.density * 8).toInt()
                        layoutParams = bLp
                        // default transparent background so host drawable still applies
                        setBackgroundColor(0x00000000)
                    }
                }

                val deleteBtn = createButton("Delete bundle")
                val closeBtn = createButton("Close")
                val saveBtn =
                        createButton("Save & Reload").apply {
                            // accent background + readable text
                            setBackgroundColor(0xFFBB86FC.toInt())
                            setTextColor(0xFF000000.toInt())
                        }

                // Add Delete and Close to the top (horizontal) row
                buttonsRow.addView(deleteBtn)
                buttonsRow.addView(closeBtn)
                container.addView(buttonsRow)

                // Create a separate row for the Save button so it sits below and can span full
                // width
                val saveRow =
                        LinearLayout(themed).apply {
                            orientation = LinearLayout.HORIZONTAL
                            val lp =
                                    LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                            lp.topMargin = (themed.resources.displayMetrics.density * 8).toInt()
                            layoutParams = lp
                        }

                val saveLp =
                        LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                saveRow.addView(saveBtn, saveLp)
                container.addView(saveRow)

                // Expose these variables below using the local names expected by the rest of the
                // function (safeSwitch, customSwitch, urlInput, saveBtn, deleteBtn, closeBtn).
                // They are already defined above as local vals.

                // Initialize state - check for any sentinel file that indicates safe mode.
                // Be robust: accept any file that starts with "00_" (legacy sentinel pattern)
                // or the legacy "rv_safe_mode.js".
                val safePresent =
                        preloadsDir.listFiles()?.any {
                            it.isFile && (it.name.startsWith("00_") || it.name == "rv_safe_mode.js")
                        }
                                ?: false
                safeSwitch.isChecked = safePresent
                customSwitch.isChecked = customEnabled
                urlInput.setText(customUrl)
                urlInput.visibility = if (customEnabled) View.VISIBLE else View.GONE

                // Confirmation modal helper (keeps previous messaging/behavior)
                fun showReloadConfirmModal(parentCtx: Context, turningOn: Boolean) {
                    try {
                        val dialogContainer =
                                LinearLayout(parentCtx).apply {
                                    orientation = LinearLayout.VERTICAL
                                    val pad =
                                            (parentCtx.resources.displayMetrics.density * 16)
                                                    .toInt()
                                    setPadding(pad, pad, pad, pad)
                                    setBackgroundColor(0xFF121212.toInt())
                                }
                        val message =
                                TextView(parentCtx).apply {
                                    text =
                                            if (turningOn) {
                                                "All add-ons will be temporarily disabled upon reload."
                                            } else {
                                                "All add-ons will load normally."
                                            }
                                    setTextColor(0xFFFFFFFF.toInt())
                                    textSize = 14f
                                    setPadding(0, 0, 0, 8)
                                }
                        dialogContainer.addView(message)

                        val buttonsRow =
                                LinearLayout(parentCtx).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    val lp =
                                            LinearLayout.LayoutParams(
                                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                            )
                                    layoutParams = lp
                                }

                        val reloadNowBtn =
                                Button(parentCtx).apply {
                                    text = "Reload Now"
                                    setTextColor(0xFF000000.toInt())
                                    setBackgroundColor(0xFFBB86FC.toInt())
                                    setOnClickListener {
                                        try {
                                            reloadApp()
                                        } catch (_: Throwable) {}
                                    }
                                }
                        val laterBtn =
                                Button(parentCtx).apply {
                                    text = "Later"
                                    setTextColor(0xFFFFFFFF.toInt())
                                }

                        val lpBtn =
                                LinearLayout.LayoutParams(
                                                0,
                                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                                1f
                                        )
                                        .apply { marginEnd = 8 }
                        val lpBtn2 =
                                LinearLayout.LayoutParams(
                                        0,
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                        1f
                                )

                        buttonsRow.addView(reloadNowBtn, lpBtn)
                        buttonsRow.addView(laterBtn, lpBtn2)

                        dialogContainer.addView(buttonsRow)

                        val dlg = AlertDialog.Builder(parentCtx).setView(dialogContainer).create()
                        laterBtn.setOnClickListener { dlg.dismiss() }
                        dlg.show()
                    } catch (t: Throwable) {
                        Log.e("Failed to show reload confirm modal: ${t.message}", t)
                        XposedBridge.log(
                                "ShiggyXposed: Reload confirm modal failed: ${android.util.Log.getStackTraceString(t)}"
                        )
                        try {
                            AlertDialog.Builder(parentCtx)
                                    .setTitle("Reload now?")
                                    .setMessage(
                                            if (turningOn)
                                                    "All add-ons will be temporarily disabled upon reload."
                                            else "All add-ons will load normally."
                                    )
                                    .setPositiveButton("Reload Now") { _, _ -> reloadApp() }
                                    .setNegativeButton("Later", null)
                                    .show()
                        } catch (_: Throwable) {}
                    }
                }

                // Safe switch toggles the safe preload file immediately (no automatic reload),
                // then prompts the user whether to reload now or later (matching React UI).
                safeSwitch.setOnCheckedChangeListener {
                        _: android.widget.CompoundButton,
                        checked: Boolean ->
                    try {
                        val safeFile = File(preloadsDir, "00_rv_safe_mode.js")
                        if (checked) {
                            val safeJs =
                                    "(function(){try{var g=(typeof globalThis!=='undefined'?globalThis:(typeof global!=='undefined'?global:this));if(!g)g={};if(!g.console)g.console={log:function(){},error:function(){},warn:function(){},info:function(){}};g.__SHIGGY_SAFE_MODE=true;}catch(e){}})();"
                            runCatching { safeFile.writeText(safeJs) }.onFailure { t ->
                                Log.e("Failed to write safe preload: ${t.message}", t)
                                XposedBridge.log(
                                        "ShiggyXposed: write safe preload failed: ${android.util.Log.getStackTraceString(t)}"
                                )
                            }
                        } else {
                            // Remove any safe-mode sentinel files (be forgiving about filenames)
                            try {
                                preloadsDir
                                        .listFiles()
                                        ?.filter {
                                            it.isFile &&
                                                    (it.name.startsWith("00_") ||
                                                            it.name == "rv_safe_mode.js")
                                        }
                                        ?.forEach { it.delete() }
                            } catch (_: Throwable) {}
                        }
                    } catch (t: Throwable) {
                        Log.e("Safe toggle persistence failed: ${t.message}", t)
                        XposedBridge.log(
                                "ShiggyXposed: Safe toggle persistence failed: ${android.util.Log.getStackTraceString(t)}"
                        )
                    }

                    // Show confirmation (no automatic reload) with option to reload now
                    showReloadConfirmModal(themed, checked)
                }

                // Custom URL toggle behavior
                customSwitch.setOnCheckedChangeListener {
                        _: android.widget.CompoundButton,
                        checked: Boolean ->
                    urlInput.visibility = if (checked) View.VISIBLE else View.GONE
                }

                // Save button: persist loader.json and safe file state, then reload
                saveBtn.setOnClickListener {
                    try {
                        val enabled = customSwitch.isChecked
                        val url = urlInput.text?.toString() ?: ""
                        val escapedUrl = url.replace("\\", "\\\\").replace("\"", "\\\"")
                        val json =
                                "{\"customLoadUrl\":{\"enabled\":$enabled,\"url\":\"$escapedUrl\"}}"
                        runCatching { configFile.writeText(json) }.onFailure { t ->
                            Log.e("Failed to write loader.json: ${t.message}", t)
                            XposedBridge.log(
                                    "ShiggyXposed: write loader.json failed: ${android.util.Log.getStackTraceString(t)}"
                            )
                        }
                        // ensure safe file persisted if switch is checked
                        val safeFile = File(preloadsDir, "00_rv_safe_mode.js")
                        if (safeSwitch.isChecked) {
                            val safeJs =
                                    "(function(){try{var g=(typeof globalThis!=='undefined'?globalThis:(typeof global!=='undefined'?global:this));if(!g)g={};if(!g.console)g.console={log:function(){},error:function(){},warn:function(){},info:function(){}};g.__SHIGGY_SAFE_MODE=true;}catch(e){}})();"
                            runCatching { safeFile.writeText(safeJs) }
                        } else {
                            // Ensure we remove any existing safe-mode sentinel files
                            try {
                                preloadsDir
                                        .listFiles()
                                        ?.filter {
                                            it.isFile &&
                                                    (it.name.startsWith("00_") ||
                                                            it.name == "rv_safe_mode.js")
                                        }
                                        ?.forEach { it.delete() }
                            } catch (_: Throwable) {}
                        }
                    } catch (t: Throwable) {
                        Log.e("Failed to save loader.json: ${t.message}", t)
                    }
                    reloadApp()
                }

                // Delete bundle
                deleteBtn.setOnClickListener {
                    try {
                        val bundleFile =
                                File(
                                        packageParam.appInfo.dataDir,
                                        "${Constants.CACHE_DIR}/${Constants.MAIN_SCRIPT_FILE}"
                                )
                        if (bundleFile.exists()) bundleFile.delete()
                    } catch (t: Throwable) {
                        Log.e("Failed to delete bundle: ${t.message}", t)
                    }
                    reloadApp()
                }

                // Close button dismisses the created dialog
                val dlg = AlertDialog.Builder(themed).setView(container).create()
                dlg.show()
                closeBtn.setOnClickListener { dlg.dismiss() }
            } catch (e: Throwable) {
                // Log and fallback to old simple dialog
                Log.e("Dialog failed, falling back: ${e.message}", e)
                XposedBridge.log(
                        "ShiggyXposed: Dialog failed: ${android.util.Log.getStackTraceString(e)}"
                )

                try {
                    AlertDialog.Builder(context)
                            .setTitle("ShiggyXposed Recovery Options")
                            .setItems(arrayOf("Reload", "Delete bundle.js")) { _, which ->
                                when (which) {
                                    0 -> reloadApp()
                                    1 -> {
                                        val bundleFile =
                                                File(
                                                        packageParam.appInfo.dataDir,
                                                        "${Constants.CACHE_DIR}/${Constants.MAIN_SCRIPT_FILE}"
                                                )
                                        if (bundleFile.exists()) bundleFile.delete()
                                        reloadApp()
                                    }
                                }
                            }
                            .show()
                } catch (t: Throwable) {
                    Log.e("Fallback dialog also failed: ${t.message}", t)
                    XposedBridge.log(
                            "ShiggyXposed: Fallback dialog failed: ${android.util.Log.getStackTraceString(t)}"
                    )
                }
            }
        }
    }
}
