package app.bambumon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import app.bambumon.ui.components.ControlButton
import app.bambumon.ui.components.ControlStyle
import app.bambumon.ui.theme.LocalBambuType
import app.bambumon.ui.theme.Palette

@Composable
fun SetupScreen(
    state: SetupState,
    prefillHost: String?,
    onConnect: (host: String, accessCode: String) -> Unit,
    onCancel: (() -> Unit)?,
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
            Text(if (updating) "UPDATE ACCESS CODE" else "CONNECT", style = t.eyebrow, color = Palette.amber)
            Text(if (updating) "New access code" else "Your printer", style = t.title.copy(fontSize = t.title.fontSize * 1.4f), color = Palette.text)
            Text(
                "Use the printer's IP address and the access code from Settings → LAN on its screen. " +
                    "The serial number is read from the printer's certificate.",
                style = t.body, color = Palette.muted,
            )
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
        }
    }
}
