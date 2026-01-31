package ShiggyXposed.xposed.modules.LogBox

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.animation.*
import android.view.animation.DecelerateInterpolator

object LogBoxComponents {

    fun createM3Button(context: Context, text: String, colors: M3Colors, onClick: () -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = LogBoxUtils.createM3Background(context, colors.primaryContainer, 20f)
            setPadding(
                LogBoxUtils.dpToPx(context, 24),
                LogBoxUtils.dpToPx(context, 10),
                LogBoxUtils.dpToPx(context, 24),
                LogBoxUtils.dpToPx(context, 10)
            )
            clipToOutline = true
            isClickable = true
            isFocusable = true

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LogBoxUtils.dpToPx(context, 40)
            ).apply {
                setMargins(0, LogBoxUtils.dpToPx(context, 8), 0, 0)
            }
            layoutParams = params

            addView(TextView(context).apply {
                this.text = text
                setTextColor(colors.onPrimaryContainer)
                textSize = 14f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                gravity = Gravity.CENTER
            })

            val rippleDrawable = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Color.argb(30, 255, 255, 255)),
                LogBoxUtils.createM3Background(context, colors.primaryContainer, 20f),
                null
            )
            background = rippleDrawable

            setOnClickListener { onClick() }
        }
    }

    fun createTitle(context: Context, colors: M3Colors, text: String, center: Boolean = false): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = if (center) 20f else 22f
            setTextColor(colors.onSurface)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, LogBoxUtils.dpToPx(context, if (center) 12 else 16))
            gravity = if (center) Gravity.CENTER else Gravity.START
        }
    }

    fun createSubtitle(context: Context, colors: M3Colors, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(colors.onSurfaceVariant)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            setPadding(0, 0, 0, LogBoxUtils.dpToPx(context, 24))
            gravity = Gravity.CENTER
        }
    }

    fun createMenuContainer(context: Context, colors: M3Colors, padding: Int = 24): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                LogBoxUtils.dpToPx(context, padding),
                LogBoxUtils.dpToPx(context, padding),
                LogBoxUtils.dpToPx(context, padding),
                LogBoxUtils.dpToPx(context, padding)
            )
            background = LogBoxUtils.createM3Background(context, colors.surface, if (padding == 24) 28f else 24f)
        }
    }

    fun createM3Switch(context: Context, colors: M3Colors, isChecked: Boolean): View {
        val switchView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = isChecked
            layoutParams = LinearLayout.LayoutParams(
                LogBoxUtils.dpToPx(context, 52),
                LogBoxUtils.dpToPx(context, 32)
            )
        }

        updateSwitchAppearance(context, switchView, colors, isChecked)
        return switchView
    }

    fun updateSwitchAppearance(context: Context, switchView: View, colors: M3Colors, isChecked: Boolean) {
        val trackColor = if (isChecked) colors.primary else colors.surfaceVariant
        val thumbColor = if (isChecked) colors.onPrimary else colors.onSurfaceVariant

        val currentTrackColor = if (switchView.background is GradientDrawable) {
            (switchView.background as GradientDrawable).color?.defaultColor
                ?: (if (!isChecked) colors.primary else colors.surfaceVariant)
        } else {
            if (!isChecked) colors.primary else colors.surfaceVariant
        }

        val trackColorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentTrackColor, trackColor)
        trackColorAnimator.duration = 250
        trackColorAnimator.interpolator = DecelerateInterpolator()
        trackColorAnimator.addUpdateListener { animator ->
            val animatedColor = animator.animatedValue as Int
            val track = GradientDrawable().apply {
                setColor(animatedColor)
                setCornerRadius(LogBoxUtils.dpToPx(context, 16).toFloat())
            }
            switchView.background = track
        }

        if (switchView is LinearLayout) {
            val thumb = if (switchView.childCount > 0) {
                switchView.getChildAt(0)
            } else {
                View(context).apply {
                    val size = LogBoxUtils.dpToPx(context, 24)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginStart = LogBoxUtils.dpToPx(context, 4)
                        marginEnd = LogBoxUtils.dpToPx(context, 24)
                    }
                    switchView.addView(this)
                }
            }

            val currentParams = thumb.layoutParams as LinearLayout.LayoutParams
            val targetMarginStart = if (isChecked) LogBoxUtils.dpToPx(context, 24) else LogBoxUtils.dpToPx(context, 4)

            val marginAnimator = ValueAnimator.ofInt(currentParams.marginStart, targetMarginStart)
            marginAnimator.duration = 250
            marginAnimator.interpolator = DecelerateInterpolator()
            marginAnimator.addUpdateListener { animator ->
                val animatedMargin = animator.animatedValue as Int
                val params = thumb.layoutParams as LinearLayout.LayoutParams
                params.marginStart = animatedMargin
                params.marginEnd = LogBoxUtils.dpToPx(context, 28) - animatedMargin
                thumb.layoutParams = params
            }

            val currentThumbColor = if (thumb.background is GradientDrawable) {
                (thumb.background as GradientDrawable).color?.defaultColor
                    ?: (if (!isChecked) colors.onPrimary else colors.onSurfaceVariant)
            } else {
                if (!isChecked) colors.onPrimary else colors.onSurfaceVariant
            }

            val thumbColorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentThumbColor, thumbColor)
            thumbColorAnimator.duration = 250
            thumbColorAnimator.interpolator = DecelerateInterpolator()
            thumbColorAnimator.addUpdateListener { animator ->
                val animatedColor = animator.animatedValue as Int
                thumb.background = GradientDrawable().apply {
                    setColor(animatedColor)
                    shape = GradientDrawable.OVAL
                }
            }

            val animatorSet = AnimatorSet()
            animatorSet.playTogether(trackColorAnimator, marginAnimator, thumbColorAnimator)
            animatorSet.start()
        }
    }

    fun createAppearanceSelector(context: Context, colors: M3Colors): View {
        val currentMode = LogBoxUtils.getAppearanceMode(context)
        val options = listOf("System", "Light", "Dark")
        val keys = listOf("system", "light", "dark")

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
            setPadding(0, 0, 0, LogBoxUtils.dpToPx(context, 8))
        }

        val label = TextView(context).apply {
            text = "Appearance"
            textSize = 14f
            setTextColor(colors.onSurface)
            setPadding(0, 0, 0, LogBoxUtils.dpToPx(context, 8))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        wrapper.addView(label)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LogBoxUtils.dpToPx(context, 40)
            )
            layoutParams = params
        }

        options.forEachIndexed { idx, title ->
            val key = keys[idx]
            val seg = TextView(context).apply {
                text = title
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                val segParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                if (idx > 0) segParams.setMargins(LogBoxUtils.dpToPx(context, 8), 0, 0, 0)
                layoutParams = segParams

                val isSelected = currentMode == key
                background = LogBoxUtils.createM3Background(
                    context,
                    if (isSelected) colors.primaryContainer else colors.surfaceVariant,
                    12f
                )
                setTextColor(if (isSelected) colors.onPrimaryContainer else colors.onSurface)
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    LogBoxUtils.setAppearanceMode(context, key)

                    for (i in 0 until row.childCount) {
                        val child = row.getChildAt(i) as TextView
                        val sel = keys[i] == key
                        child.background = LogBoxUtils.createM3Background(
                            context,
                            if (sel) colors.primaryContainer else colors.surfaceVariant,
                            12f
                        )
                        child.setTextColor(if (sel) colors.onPrimaryContainer else colors.onSurface)
                    }
                }
            }
            row.addView(seg)
        }

        wrapper.addView(row)
        return wrapper
    }
}
