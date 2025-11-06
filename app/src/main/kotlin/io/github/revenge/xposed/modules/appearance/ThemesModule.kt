package GoonXposed.xposed.modules.appearance

import GoonXposed.xposed.Module
import GoonXposed.xposed.Constants
import GoonXposed.xposed.Utils.Companion.JSON
import GoonXposed.xposed.MethodHookBuilder
// import GoonXposed.xposed.modules.appearance.ThemesModule
import android.content.Context
import android.content.res.Resources
import androidx.core.graphics.toColorInt
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
