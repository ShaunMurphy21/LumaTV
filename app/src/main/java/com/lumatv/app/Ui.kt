package com.lumatv.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    const val BG = 0xFF080A10.toInt()
    const val SURFACE = 0xFF161A28.toInt()
    const val PANEL = 0xFF1D2233.toInt()
    const val WHITE = 0xFFF5F6FC.toInt()
    const val MUTED = 0xFF939BAF.toInt()
    const val ACCENT = 0xFF9886FF.toInt()
    const val MINT = 0xFF54D8C5.toInt()

    fun Context.dp(n: Number): Int = (n.toFloat() * resources.displayMetrics.density + .5f).toInt()

    fun bg(color: Int, radius: Float = 14f, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = radius
            if (stroke != null) setStroke(2, stroke)
        }

    fun gradient(radius: Float = 20f): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF363168.toInt(), 0xFF151E36.toInt(), 0xFF0F2730.toInt())
    ).apply { cornerRadius = radius }

    fun text(context: Context, value: String, size: Float = 14f, color: Int = WHITE, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = true
        }

    fun button(context: Context, label: String, solid: Boolean = true, onClick: () -> Unit): TextView =
        text(context, label, 14f, if (solid) BG else WHITE, true).apply {
            gravity = Gravity.CENTER; isClickable = true; isFocusable = true
            background = bg(if (solid) MINT else PANEL, context.dp(14).toFloat())
            setPadding(context.dp(20), context.dp(13), context.dp(20), context.dp(13))
            setOnClickListener { onClick() }
        }

    fun vertical(context: Context, gap: Int = 0): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun horizontal(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun image(context: Context, heightDp: Int = 174): ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = gradient(context.dp(14).toFloat())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(heightDp))
        clipToOutline = true
    }

    fun View.pad(horizontal: Int, vertical: Int) {
        setPadding(context.dp(horizontal), context.dp(vertical), context.dp(horizontal), context.dp(vertical))
    }

    fun spacer(context: Context, height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, context.dp(height))
    }

    fun columnParams(context: Context, bottom: Int = 0, top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(top); bottomMargin = context.dp(bottom)
        }

    fun artworkCardWidth(context: Context): Int {
        val tabletLike = context.resources.configuration.screenWidthDp >= 600
        return context.dp(if (tabletLike) 188 else 147)
    }
}
