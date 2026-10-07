package app.bambumon.ui.history

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bambumon.R
import app.bambumon.history.Outcome
import app.bambumon.history.PrintHistory
import app.bambumon.history.PrintRecord
import app.bambumon.ui.components.Format
import app.bambumon.ui.components.Glyph
import app.bambumon.ui.theme.LocalBambuType
import app.bambumon.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HistoryScreen(records: List<PrintRecord>, history: PrintHistory, onBack: () -> Unit) {
    val t = LocalBambuType.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    records.firstOrNull { it.key == open }?.let { r ->
        HistoryEntryScreen(r, onBack = { open = null })
        return
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Palette.ground).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Palette.card)
                    .clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Glyph(R.drawable.ic_close, Palette.text, 18.dp, description = "Back") }
            Text("Print history", style = t.title, color = Palette.text)
        }
        if (records.isEmpty()) {
            Text(
                "No prints yet. Prints Bambumon follows in the background are recorded here, with a final snapshot.",
                style = t.body, color = Palette.muted, modifier = Modifier.padding(24.dp).widthIn(max = 480.dp),
            )
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
        ) {
            items(records, key = { it.key }) { r ->
                Box(Modifier.clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClickLabel = "Open ${r.jobName ?: "print"}") { open = r.key }) {
                    HistoryRow(r, history)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(r: PrintRecord, history: PrintHistory) {
    val t = LocalBambuType.current
    val thumb by produceState<ImageBitmap?>(null, r.snapshotFile) {
        value = withContext(Dispatchers.IO) {
            history.snapshot(r)?.let { f ->
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
            }
        }
    }
    val (label, color) = when (r.outcome) {
        Outcome.Finished -> "Finished" to Palette.blue
        Outcome.Failed -> "Failed" to Palette.danger
        Outcome.InProgress -> "In progress" to Palette.amber
        Outcome.Unknown -> "Ended" to Palette.muted
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.card).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(width = 104.dp, height = 64.dp).clip(RoundedCornerShape(12.dp)).background(Palette.well)) {
            thumb?.let { Image(it, contentDescription = "Final snapshot", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.jobName ?: "Untitled print", style = t.section, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(dateTime(r.firstSeenAtMs), r.endedAtMs?.let { "followed ${Format.duration(((it - r.firstSeenAtMs) / 60_000).toInt())}" })
                    .joinToString(" · "),
                style = t.caption, color = Palette.muted, maxLines = 1,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label.uppercase(), style = t.eyebrow, color = color)
                r.printError?.let { Text("error $it", style = t.mono, color = Palette.muted) }
                r.totalLayers?.let { Text("$it layers", style = t.caption, color = Palette.faint) }
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private fun dateTime(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateFormat)
