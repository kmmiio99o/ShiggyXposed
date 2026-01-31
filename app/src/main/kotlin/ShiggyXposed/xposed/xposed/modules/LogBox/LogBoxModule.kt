package ShiggyXposed.xposed.modules.LogBox

import ShiggyXposed.xposed.Module
import ShiggyXposed.xposed.Utils.Log
import android.content.Context
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*

object LogBoxModule : Module() {
    lateinit var packageParam: XC_LoadPackage.LoadPackageParam
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    var contextForMenu: Context? = null

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        this@LogBoxModule.packageParam = packageParam

        try {
            val dcdReactNativeHostClass = classLoader.loadClass("com.discord.bridge.DCDReactNativeHost")
            val getUseDeveloperSupportMethod =
                dcdReactNativeHostClass.methods.first { it.name == "getUseDeveloperSupport" }

            getUseDeveloperSupportMethod.hook {
                before {
                    result = true
                }
            }
            Log.e("Successfully hooked DCDReactNativeHost")
        } catch (e: Exception) {
            Log.e("Failed to hook DCDReactNativeHost: ${e.message}")
        }

        return@with
    }

    override fun onContext(context: Context) {
        try {
            Log.e("onContext called with context: $context")
            contextForMenu = context

            val possibleClasses = listOf(
                "com.facebook.react.devsupport.BridgeDevSupportManager",
                "com.facebook.react.devsupport.BridgelessDevSupportManager",
                "com.facebook.react.devsupport.DevSupportManagerImpl",
                "com.facebook.react.devsupport.DevSupportManagerBase",
                "com.facebook.react.devsupport.DefaultDevSupportManager"
            )

            var foundAny = false
            possibleClasses.forEach { className ->
                try {
                    val clazz = packageParam.classLoader.loadClass(className)
                    Log.e("Found class: $className")
                    hookDevSupportManager(clazz, context)
                    foundAny = true
                } catch (e: Exception) {
                    Log.e("Class not found: $className - ${e.message}")
                }
            }

            if (!foundAny) {
                tryFindDevSupportClasses(context)
            }
        } catch (e: Exception) {
            // Handle exception
        }
    }

    private fun tryFindDevSupportClasses(context: Context) {
        try {
            val dexFile = packageParam.classLoader.javaClass.getDeclaredField("pathList")
            dexFile.isAccessible = true
            Log.e("Searching for DevSupport classes in classloader...")
        } catch (e: Exception) {
            Log.e("Could not search for classes: ${e.message}")
        }
    }

    private fun hookDevSupportManager(clazz: Class<*>, context: Context) {
        Log.e("Attempting to hook ${clazz.name}")

        Log.e("Available methods in ${clazz.simpleName}:")
        clazz.methods.forEach { method ->
            if (method.name.contains("Dev") || method.name.contains("Reload") || method.name.contains("Options")) {
                Log.e("  - ${method.name}")
            }
        }

        try {
            try {
                val handleReloadJSMethod = clazz.methods.firstOrNull { it.name == "handleReloadJS" }
                if (handleReloadJSMethod != null) {
                    XposedBridge.hookMethod(handleReloadJSMethod, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? {
                            Log.e("handleReloadJS called - reloading app")
                            ShiggyXposed.xposed.Utils.Companion.reloadApp()
                            return null
                        }
                    })
                }
            } catch (e: Exception) {
                Log.e("Failed to hook handleReloadJS: ${e.message}")
            }

            try {
                val showDevOptionsDialogMethod = clazz.methods.firstOrNull { it.name == "showDevOptionsDialog" }
                if (showDevOptionsDialogMethod != null) {
                    XposedBridge.hookMethod(showDevOptionsDialogMethod, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? {
                            try {
                                var activityContext: Context? = null
                                try {
                                    activityContext = getContextFromDevSupport(clazz, param.thisObject)
                                    if (activityContext != null) {
                                        Log.e("Successfully got context from DevSupport")
                                    }
                                } catch (e: Exception) {
                                    Log.e("Failed to get context from DevSupport (non-fatal): ${e.message}")
                                }

                                val finalContext = activityContext ?: contextForMenu ?: context
                                Log.e("Using context: $finalContext (type: ${finalContext.javaClass.name})")

                                LogBoxNavigation.showRecoveryMenu(finalContext)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            return null
                        }
                    })
                }
            } catch (e: Exception) {
                // Handle exception
            }
        } catch (e: Exception) {
            // Handle exception
        }
    }

    private fun getContextFromDevSupport(clazz: Class<*>, instance: Any?): Context? {
        if (instance == null) {
            Log.e("getContextFromDevSupport: instance is null")
            return null
        }

        return try {
            val helpers = listOf(
                "mReactInstanceDevHelper",
                "reactInstanceDevHelper",
                "mReactInstanceManager",
                "mApplicationContext"
            )

            for (helperName in helpers) {
                try {
                    Log.e("Trying field: $helperName")
                    val helperField = XposedHelpers.findFieldIfExists(clazz, helperName)
                    if (helperField == null) {
                        Log.e("Field $helperName not found, skipping")
                        continue
                    }

                    val helper = helperField.get(instance)
                    if (helper == null) {
                        Log.e("Field $helperName is null, skipping")
                        continue
                    }

                    if (helper is Context) {
                        Log.e("Field $helperName is a Context, returning it")
                        return helper
                    }

                    val getCurrentActivityMethod = helper.javaClass.methods.firstOrNull {
                        it.name == "getCurrentActivity"
                    }

                    if (getCurrentActivityMethod != null) {
                        val ctx = getCurrentActivityMethod.invoke(helper) as? Context
                        if (ctx != null) {
                            Log.e("Got context from $helperName.getCurrentActivity()")
                            return ctx
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Error trying $helperName: ${e.message}")
                }
            }

            Log.e("Could not get context from DevSupport object using any method")
            null
        } catch (e: Exception) {
            Log.e("Failed to get context (outer catch): ${e.message}")
            null
        }
    }
}
