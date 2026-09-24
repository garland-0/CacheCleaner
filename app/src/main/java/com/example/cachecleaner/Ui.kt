package com.example.cachecleaner

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val Bg         = Color(0xFF222228)
val SurfaceC   = Color(0xFF2B2B32)
val DarkSh     = Color(0xFF141419)
val LightSh    = Color(0xFF3B3B46)
val TextP      = Color(0xFFEDEDF2)
val TextS      = Color(0xFF8E8E99)
val Accent     = Color(0xFFFF5A2A)
val AccentDark = Color(0xFFC23E12)
val TrackC     = Color(0xFF1D1D23)

val ZenDots = FontFamily(Font(R.font.zen_dots, FontWeight.Normal))

/** Soft neumorphic card: light shadow top-left, dark shadow bottom-right. */
@Composable
fun NeuCard(
    modifier: Modifier = Modifier,
    corner: Dp = 24.dp,
    pad: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.padding(horizontal = 5.dp, vertical = 6.dp)) {
        Box(Modifier.matchParentSize().offset(4.dp, 5.dp).shadow(12.dp, shape, clip = false, ambientColor = DarkSh, spotColor = DarkSh))
        Box(Modifier.matchParentSize().offset((-4).dp, (-5).dp).shadow(12.dp, shape, clip = false, ambientColor = LightSh, spotColor = LightSh))
        Box(Modifier.clip(shape).background(SurfaceC).padding(pad)) { content() }
    }
}

/** Circular neumorphic button; orange when accent = true. */
@Composable
fun NeuCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 58.dp,
    accent: Boolean = false,
    enabled: Boolean = true,
    icon: @Composable () -> Unit
) {
    Box(
        modifier
            .size(size)
            .shadow(
                if (accent) 14.dp else 7.dp,
                CircleShape,
                clip = false,
                ambientColor = DarkSh,
                spotColor = if (accent) AccentDark else DarkSh
            )
            .clip(CircleShape)
            .background(if (accent) Accent else SurfaceC)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { icon() }
}

/** Circular app icon. */
@Composable
fun AppIcon(entry: AppEntry, size: Dp = 46.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(TrackC),
        contentAlignment = Alignment.Center
    ) {
        entry.icon?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}
