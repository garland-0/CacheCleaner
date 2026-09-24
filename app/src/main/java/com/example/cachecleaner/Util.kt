package com.example.cachecleaner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import java.util.Locale

data class AppEntry(
    val packageName: String,
    val label: String,
    val cacheBytes: Long,
    val icon: ImageBitmap? = null,
    val selected: Boolean = true
)

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

/** Renders an app icon to a square bitmap; insets adaptive icons so they look right in a circle. */
fun drawableToBitmap(drawable: Drawable, size: Int = 128): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val d = drawable.mutate()
    val inset = if (d is AdaptiveIconDrawable) (size * 0.18f).toInt() else 0
    d.setBounds(inset, inset, size - inset, size - inset)
    d.draw(canvas)
    return bitmap
}
