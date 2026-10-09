package app.layerwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.layerwatch.ui.components.ControlButton
import app.layerwatch.ui.components.ControlStyle
import app.layerwatch.ui.theme.LocalBambuType
import app.layerwatch.ui.theme.Palette

@Composable
fun SetupScreen(
    state: SetupState,
    prefillHost: String?,
    onConnect: (host: String, accessCode: String) -> Unit,
    onCancel: (() -> Unit)?,
    onDemo: (() -> Unit)? = null,
) {
    val t = LocalBambuType.current
    var host by rememberSaveable { mutableStateOf(prefillHost.orEmpty()) }
    var code by rememberSaveable { mutableStateOf("") }
    val checking = state is SetupState.Checking
    val updating = prefillHost != null

    Column(
        Modifier.fillMaxSize().background(Palette.ground).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (updating) "UPDATE ACCESS CODE" else "WELCOME TO LAYERWATCH", style = t.eyebrow, color = Palette.amber)
            Text(
                if (updating) "New access code" else "Connect your Bambu Lab printer",
                style = t.title.copy(fontSize = t.title.fontSize * 1.4f), color = Palette.text,
            )
            if (updating) {
                Text(
                    "Enter the access code shown in the printer's network settings. It changes whenever it's regenerated on the printer.",
                    style = t.body, color = Palette.muted,
                )
            } else {
                Text(
                    "Layerwatch talks to your printer directly over your own network: no cloud account. " +
                        "Your phone needs to reach the printer, on the same WiFi or through a VPN into that network.",
                    style = t.body, color = Palette.muted,
                )
                PrinterChecklist()
            }
            val colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.amber, unfocusedBorderColor = Palette.outline,
                focusedLabelColor = Palette.amber, unfocusedLabelColor = Palette.muted,
                cursorColor = Palette.amber, focusedTextColor = Palette.text, unfocusedTextColor = Palette.text,
            )
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("Printer IP address") }, singleLine = true, enabled = !checking,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                colors = colors, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                label = { Text("Access code") }, singleLine = true, enabled = !checking,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                colors = colors, modifier = Modifier.fillMaxWidth(),
            )
            when (state) {
                is SetupState.Failed -> Text(state.message, style = t.body, color = Palette.errorText)
                is SetupState.Checking -> Text(state.step, style = t.body, color = Palette.muted)
                SetupState.Idle -> Unit
            }
            ControlButton(
                if (checking) "Checking…" else "Connect", ControlStyle.Primary,
                onClick = { onConnect(host, code) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !checking && host.isNotBlank() && code.isNotBlank(),
            ) {}
            if (onCancel != null) {
                ControlButton("Cancel", ControlStyle.Secondary, onCancel, Modifier.fillMaxWidth(), enabled = !checking, minHeight = 48.dp) {}
            }
            if (onDemo != null && !updating) {
                Text("No printer at hand?", style = t.label, color = Palette.muted, modifier = Modifier.padding(top = 8.dp))
                ControlButton("Try the demo", ControlStyle.Secondary, onDemo, Modifier.fillMaxWidth(), enabled = !checking, minHeight = 48.dp) {}
                Text("A simulated printer runs a short sample print, so you can explore every screen.", style = t.caption, color = Palette.faint)
            }
        }
    }
}

/** What to switch on at the printer, in the order people meet it on the printer's screen. */
@Composable
private fun PrinterChecklist() {
    val t = LocalBambuType.current
    val steps = listOf(
        "Open the printer's network settings on its screen (Network on X1 and H2, WLAN on P1 and A1).",
        "Turn on LAN Only Mode, then Developer Mode. Recent firmware requires both for apps other than Bambu's own. " +
            "LAN Only Mode disconnects the printer from Bambu's cloud and the Bambu Handy app.",
        "X1 and H2 series: turn on LAN Only Liveview to see the camera.",
        "Note the IP address and the access code shown there, and enter them below.",
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("On the printer", style = t.section, color = Palette.text)
        steps.forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${i + 1}", style = t.mono, color = Palette.amber)
                Text(step, style = t.label, color = Palette.muted)
            }
        }
    }
}
