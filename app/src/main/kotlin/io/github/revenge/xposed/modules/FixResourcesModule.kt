package cocobo1.pupu.xposed.modules

import android.content.res.Resources
import de.robv.android.xposed.callbacks.XC_LoadPackage
import cocobo1.pupu.xposed.Constants.Companion.TARGET_PACKAGE
import cocobo1.pupu.xposed.Module

/**
 * Hooks [Resources.getIdentifier] to fix resource package name mismatch.
 */
class FixResourcesModule : Module() {
    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        if (packageName != TARGET_PACKAGE) {
            Resources::class.java.hookMethod(
                "getIdentifier", String::class.java, String::class.java, String::class.java
            ) {
                before {
                    if (args[2] == packageName) args[2] = TARGET_PACKAGE
                }
            }
        }
    }
}