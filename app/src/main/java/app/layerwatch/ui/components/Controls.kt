package app.layerwatch.ui.components

import androidx.compose.animation.core.Animatable
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
enum class ControlStyle(val bg: Color, val fg: Color, val border: Color?, val caption: Color, val fill: Color) {
    Primary(Palette.amber, Palette.onAmber, null, Palette.onAmber, Palette.onAmber.copy(alpha = 0.18f)),
    Secondary(Palette.card, Palette.text, Palette.outline, Palette.muted, Palette.text.copy(alpha = 0.14f)),
    Danger(Palette.dangerSoft, Palette.danger, Palette.dangerOutline, Palette.dangerCaption, Palette.danger.copy(alpha = 0.22f)),
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
 * hard to trigger by accident. A fill sweeps across while held; releasing early rewinds
 * it and nothing is sent.
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
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val confirm by rememberUpdatedState(onConfirm)
    val seconds = holdMillis / 1000
    val c = style.colors(enabled)

    Box(
        modifier
            .heightIn(min = minHeight)
            .clip(ControlShape)
            .background(c.bg)
            .then(c.border?.let { Modifier.border(1.dp, it, ControlShape) } ?: Modifier)
            .semantics {
                role = Role.Button
                contentDescription = "$label. Press and hold for $seconds seconds."
                if (!enabled) disabled()
            }
            .pointerInput(enabled, holdMillis) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val hold = scope.launch {
                        val remaining = ((1f - progress.value) * holdMillis).toInt()
                        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        confirm()
                        progress.animateTo(0f, tween(600))
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f && hold.isActive) {
                        hold.cancel()
                        scope.launch { progress.animateTo(0f, tween(250)) }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress.value)
                .background(style.fill)
        )
        val holding = progress.value > 0f && progress.value < 1f
        ButtonContent(
            label = if (holding) "Keep holding…" else label,
            caption = if (holding) null else "hold $seconds s",
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
