package app.layerwatch.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.layerwatch.ui.theme.LocalBambuType
import app.layerwatch.ui.theme.Palette
import kotlinx.coroutines.launch

/** Visual treatments for control buttons. */
enum class ControlStyle(val bg: Color, val fg: Color, val border: Color?, val caption: Color, val accent: Color) {
    Primary(Palette.amber, Palette.onAmber, null, Palette.onAmber, Palette.onAmber),
    Secondary(Palette.card, Palette.text, Palette.outline, Palette.muted, Palette.amber),
    Danger(Palette.dangerSoft, Palette.danger, Palette.dangerOutline, Palette.dangerCaption, Palette.danger),
}

private val ControlShape = RoundedCornerShape(18.dp)

/**
 * Resolved colors for a button. Disabled is expressed in the colors themselves rather
 * than with Modifier.alpha: a graphics layer nested inside the clipped background lost
 * the button's own background and border after the app returned from the background.
 */
private data class ControlColors(val bg: Color, val fg: Color, val border: Color?, val caption: Color)

private fun ControlStyle.colors(enabled: Boolean): ControlColors =
    if (enabled) ControlColors(bg, fg, border, caption)
    else ControlColors(
        bg = lerp(bg, Palette.ground, 0.55f),
        fg = fg.copy(alpha = 0.45f),
        border = border?.let { lerp(it, Palette.ground, 0.5f) },
        caption = caption.copy(alpha = 0.45f),
    )

/** A plain tap button in the dashboard's style. */
@Composable
fun ControlButton(
    label: String,
    style: ControlStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 56.dp,
    stacked: Boolean = false,
    stateDescription: String? = null,
    icon: @Composable () -> Unit,
) {
    val t = LocalBambuType.current
    val c = style.colors(enabled)
    Box(
        modifier
            .heightIn(min = minHeight)
            .clip(ControlShape)
            .background(c.bg)
            .then(c.border?.let { Modifier.border(1.dp, it, ControlShape) } ?: Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { stateDescription?.let { this.stateDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        ButtonContent(label, null, c, stacked, icon, t.button)
    }
}

/**
 * A button that only fires after being held for [holdMillis]: pause and stop must be
 * hard to trigger by accident. While held it shows, all at once: an outline tracing the
 * button's edge (a full loop = done), a fill sweeping across, a countdown in place of the
 * label, a haptic tick each second, and a slight press-in. On completion it flashes and
 * confirms with a haptic; releasing early rewinds it and nothing is sent.
 *
 * Everything is drawn on this node's own canvas; no graphics layer sits inside the
 * clipped background (see ControlColors for why).
 */
@Composable
fun HoldToConfirmButton(
    label: String,
    style: ControlStyle,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdMillis: Int = 4_000,
    minHeight: Dp = 56.dp,
    stacked: Boolean = false,
    icon: @Composable () -> Unit,
) {
    val t = LocalBambuType.current
    val progress = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val confirm by rememberUpdatedState(onConfirm)
    val seconds = holdMillis / 1000
    val c = style.colors(enabled)
    val accent = style.accent
    val press by animateFloatAsState(if (holding) 0.97f else 1f, tween(120), label = "press")
    val secondsLeft = kotlin.math.ceil((1f - progress.value) * holdMillis / 1000f).toInt()

    // A tick as each second passes.
    LaunchedEffect(secondsLeft, holding) {
        if (holding && secondsLeft in 1 until seconds) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    Box(
        modifier
            .graphicsLayer { scaleX = press; scaleY = press } // outermost: wraps the clip, never inside it
            .heightIn(min = minHeight)
            .clip(ControlShape)
            .background(c.bg)
            .then(c.border?.let { Modifier.border(1.dp, it, ControlShape) } ?: Modifier)
            .drawWithContent {
                // Fill sweeping left to right, then the label on top.
                if (progress.value > 0f) drawRect(accent.copy(alpha = 0.22f), size = size.copy(width = size.width * progress.value))
                if (flash.value > 0f) drawRect(accent.copy(alpha = 0.45f * flash.value))
                drawContent()
                // Outline tracing the edge clockwise from top-centre.
                if (progress.value > 0f) {
                    val inset = 1.5.dp.toPx()
                    val r = 18.dp.toPx() - inset
                    val outline = Path().apply {
                        addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(r)))
                    }
                    val measure = PathMeasure().apply { setPath(outline, true) }
                    val total = measure.length
                    // A rounded rect's path starts where the top edge leaves the top-left corner
                    // and runs clockwise; offset by half the straight top edge to start top-centre.
                    val startAt = (size.width / 2 - inset - r).coerceAtLeast(0f)
                    val run = total * progress.value
                    val seg = Path()
                    val end = startAt + run
                    measure.getSegment(startAt, minOf(end, total), seg, true)
                    if (end > total) measure.getSegment(0f, end - total, seg, true)
                    drawPath(seg, accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = "$label. Press and hold for $seconds seconds."
                if (!enabled) disabled()
            }
            .pointerInput(enabled, holdMillis) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    holding = true
                    sent = false
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val hold = scope.launch {
                        val remaining = ((1f - progress.value) * holdMillis).toInt()
                        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
                        holding = false
                        sent = true
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        confirm()
                        flash.snapTo(1f)
                        launch { flash.animateTo(0f, tween(700)) }
                        progress.animateTo(0f, tween(700))
                        kotlinx.coroutines.delay(600)
                        sent = false
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f && hold.isActive) {
                        hold.cancel()
                        holding = false
                        scope.launch { progress.animateTo(0f, tween(250)) }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        ButtonContent(
            label = when {
                sent -> "Sent ✓"
                holding -> "Hold · $secondsLeft"
                else -> label
            },
            caption = if (holding || sent) null else "hold $seconds s",
            colors = c, stacked = stacked, icon = icon, textStyle = t.button,
        )
    }
}

@Composable
private fun ButtonContent(
    label: String,
    caption: String?,
    colors: ControlColors,
    stacked: Boolean,
    icon: @Composable () -> Unit,
    textStyle: androidx.compose.ui.text.TextStyle,
) {
    val t = LocalBambuType.current
    if (stacked) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            icon()
            Text(label, style = textStyle, color = colors.fg, maxLines = 1)
            caption?.let { Text(it, style = t.tiny, color = colors.caption) }
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                icon()
                Text(label, style = textStyle, color = colors.fg, maxLines = 1)
            }
            caption?.let { Text(it, style = t.tiny, color = colors.caption) }
        }
    }
}
