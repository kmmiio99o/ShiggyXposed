package GoonXposed.xposed.modules.no_track

import android.content.Context
import de.robv.android.xposed.callbacks.XC_LoadPackage
import GoonXposed.xposed.Module
import GoonXposed.xposed.Utils.Log

/**
 * Hooks Discord's deep links tracking to disable AppsFlyer initialization.
 */
object BlockDeepLinksTrackingModule : Module() {
    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        val deepLinksClass = classLoader.safeLoadClass("com.discord.deep_link.DeepLinks")
        deepLinksClass?.hookMethod(
            "init", Context::class.java
        ) {
            before {
                Log.i("Blocked DeepLinks tracking initialization")
                result = null
            }
        }

        return@with
    }
}
