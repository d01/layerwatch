package app.bambumon.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.bambumon.MainActivity
import app.bambumon.ui.components.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Home-screen widget: current job, progress, time left and a recent chamber frame. */
class PrinterWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = withContext(Dispatchers.IO) { WidgetStore.read(context) }
        val frame = withContext(Dispatchers.IO) { WidgetStore.readFrame(context) }
        provideContent { WidgetContent(state, frame) }
    }

    private companion object {
        val SMALL = DpSize(120.dp, 80.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val TALL = DpSize(250.dp, 220.dp)
    }
}

class PrinterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PrinterWidget()
}

// Same palette as the app.
private val Ground = Color(0xFF0B0D0F)
private val Track = Color(0xFF262B30)
private val TextMain = Color(0xFFEDEFF1)
private val Muted = Color(0xFFA2AAB2)
private val Amber = Color(0xFFF4B04A)

@Composable
private fun WidgetContent(state: WidgetState?, frame: Bitmap?) {
    val size = LocalSize.current
    Column(
        GlanceModifier.fillMaxSize().background(Ground).cornerRadius(24.dp).padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        if (state == null) {
            Text("Open Bambumon to connect", style = TextStyle(color = ColorProvider(Muted), fontSize = 13.sp))
            return@Column
        }
        when {
            size.height >= 200.dp -> {
                frame?.let {
                    Image(
                        ImageProvider(it), "Chamber camera",
                        GlanceModifier.fillMaxWidth().defaultWeight().cornerRadius(16.dp),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(GlanceModifier.height(10.dp))
                }
                Details(state, compact = false)
            }
            size.width >= 250.dp && frame != null -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    ImageProvider(frame), "Chamber camera",
                    GlanceModifier.width(120.dp).fillMaxHeight().cornerRadius(14.dp),
                    contentScale = ContentScale.Crop,
                )
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) { Details(state, compact = false) }
            }
            else -> Details(state, compact = size.width < 250.dp)
        }
    }
}

@Composable
private fun Details(s: WidgetState, compact: Boolean) {
    val accent = if (s.active) Amber else Muted
    if (!compact) {
        Text(
            "${s.model} · ${s.phase}",
            style = TextStyle(color = ColorProvider(accent), fontSize = 11.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Text(s.jobName ?: "—", style = TextStyle(color = ColorProvider(TextMain), fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(s.percent?.toString() ?: "—", style = TextStyle(color = ColorProvider(TextMain), fontSize = if (compact) 30.sp else 34.sp, fontWeight = FontWeight.Medium))
        Text("%", style = TextStyle(color = ColorProvider(Muted), fontSize = 16.sp))
    }
    LinearProgressIndicator(
        progress = (s.percent ?: 0) / 100f,
        modifier = GlanceModifier.fillMaxWidth().height(5.dp),
        color = ColorProvider(accent),
        backgroundColor = ColorProvider(Track),
    )
    Spacer(GlanceModifier.height(6.dp))
    val line = buildList {
        s.remainingMinutes?.let { add("${Format.duration(it)} left") }
        add("as of ${Format.clockTime(s.reportAtMs)}")
    }.joinToString(" · ")
    Text(line, style = TextStyle(color = ColorProvider(Muted), fontSize = 11.sp), maxLines = 1)
}
