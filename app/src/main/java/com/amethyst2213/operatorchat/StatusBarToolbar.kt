package com.amethyst2213.operatorchat

import android.content.Context
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Makes the toolbar span behind the status bar (no blank band on
 * edge-to-edge / targetSdk 35): the toolbar height grows by the status-bar
 * inset and its content is padded down below it, so the purple bar extends
 * to the very top of the screen.
 */
object StatusBarToolbar {

    fun apply(ctx: Context, toolbar: Toolbar) {
        val actionBarSize = actionBarSize(ctx)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (top > 0 && v.height <= actionBarSize) {
                v.layoutParams = v.layoutParams.apply { height = actionBarSize + top }
            }
            v.setPadding(0, top, 0, 0)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)
    }

    private fun actionBarSize(ctx: Context): Int {
        val a = ctx.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.actionBarSize))
        val size = a.getDimensionPixelSize(0, 0)
        a.recycle()
        return if (size > 0) size else 56 * (ctx.resources.displayMetrics.density).toInt()
    }
}