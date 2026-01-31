package ShiggyXposed.xposed.modules.LogBox

import android.graphics.Color

data class M3Colors(
    val surface: Int,
    val surfaceVariant: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val error: Int,
    val onError: Int
)

object LogBoxConstants {
    const val TAG = "LogBoxModule"

    val FLAVOR_COLORS = mapOf(
        "blue" to Pair("#0D47A1", "#82B1FF"),
        "green" to Pair("#1B5E20", "#A5D6A7"),
        "mocha" to Pair("#3E2723", "#BCAAA4"),
        "vanilla" to Pair("#F9A825", "#FFF59D"),
        "purple" to Pair("#6A1B9A", "#E1BEE7"),
        "amber" to Pair("#FF6F00", "#FFE0B2"),
        "teal" to Pair("#004D40", "#80CBC4")
    )

    val APPEARANCE_MODES = listOf("system", "light", "dark")
    val FLAVORS = listOf("blue", "green", "mocha", "vanilla", "purple", "amber", "teal")
}
