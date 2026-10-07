package app.bambumon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SetupScreen(state: SetupState, onConnect: (host: String, accessCode: String) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val checking = state is SetupState.Checking

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Connect to printer", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Enter the printer's IP address and the access code shown on its " +
                "Settings → LAN screen. The serial number is read from the printer.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Printer IP address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Access code") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        )
        if (state is SetupState.Failed) {
            Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.widthIn(max = 480.dp))
        }
        Button(
            onClick = { onConnect(host, code) },
            enabled = !checking && host.isNotBlank() && code.isNotBlank(),
        ) {
            if (checking) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            Text(if (checking) "Checking…" else "Connect")
        }
    }
}
