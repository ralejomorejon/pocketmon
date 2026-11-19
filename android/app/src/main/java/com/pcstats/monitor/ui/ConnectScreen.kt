package com.pcstats.monitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import com.pcstats.monitor.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcstats.monitor.data.SavedPc
import com.pcstats.monitor.net.ConnectionState
import com.pcstats.monitor.net.DiscoveredPc
import com.pcstats.monitor.protocol.USB_HOST

@Composable
fun ConnectScreen(
    state: ConnectionState,
    discovered: List<DiscoveredPc>,
    savedPc: SavedPc?,
    onPair: (host: String, code: String) -> Unit,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(savedPc?.lastHost ?: "") }
    var code by rememberSaveable { mutableStateOf("") }
    val busy = state is ConnectionState.Connecting
    val canPair = host.isNotBlank() && code.length == 6 && !busy

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Black)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(
                painter = painterResource(R.drawable.pocketmon_logo),
                contentDescription = "PocketMon",
                modifier = Modifier.height(44.dp),
            )
            Text(
                "Pair this phone with the PocketMon agent running on your Windows PC.",
                color = Palette.Label, fontSize = 14.sp,
            )
            ModeExplainer(
                "Wi-Fi",
                "Phone and PC on the same network. PCs announcing themselves appear below, or type the PC's IP address.",
            )
            ModeExplainer(
                "USB",
                "Plug the phone into the PC with USB debugging enabled. The PC agent runs adb reverse automatically, " +
                    "so the app reaches it at $USB_HOST. Choose \"USB cable\" below.",
            )

            SectionLabel("PCs on this network")
            if (discovered.isEmpty()) {
                Text("Searching…", color = Palette.LabelDim, fontSize = 13.sp)
            }
            discovered.forEach { pc ->
                HostRow(pc.name, pc.host, selected = host == pc.host) { host = pc.host }
            }
            HostRow("USB cable", "$USB_HOST (adb reverse)", selected = host == USB_HOST) { host = USB_HOST }

            SectionLabel("Manual")
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("PC address (IP or IP:port)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { v -> code = v.filter(Char::isDigit).take(6) },
                label = { Text("6-digit code from the PC tray") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            val message = when (state) {
                is ConnectionState.NeedsPairing -> state.message
                is ConnectionState.Connecting -> "Connecting to ${state.host ?: "PC"}…"
                is ConnectionState.Error -> state.message
                else -> null
            }
            if (message != null) {
                Text(message, color = if (state is ConnectionState.NeedsPairing) Palette.Warm else Palette.Label, fontSize = 13.sp)
            }

            Button(
                onClick = { onPair(host, code) },
                enabled = canPair,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Black,
                    contentColor = Palette.Value,
                    disabledContainerColor = Palette.Black,
                    disabledContentColor = Palette.LabelDim,
                ),
                border = BorderStroke(1.dp, if (canPair) Palette.Accent else Palette.Outline),
            ) { Text("Pair") }

            if (savedPc?.token != null) {
                OutlinedButton(
                    onClick = onReconnect,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Palette.Outline),
                ) { Text("Reconnect to ${savedPc.name}", color = Palette.Value) }
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Palette.Outline),
            ) { Text("Settings", color = Palette.Label) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModeExplainer(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Palette.Outline, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text(title, color = Palette.Value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(body, color = Palette.Label, fontSize = 13.sp)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = Palette.Label, fontSize = 11.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun HostRow(name: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (selected) Palette.Accent else Palette.Outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = Palette.Value, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(detail, color = Palette.Label, fontSize = 12.sp)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Accent,
    unfocusedBorderColor = Palette.Outline,
    focusedLabelColor = Palette.Accent,
    unfocusedLabelColor = Palette.Label,
    focusedTextColor = Palette.Value,
    unfocusedTextColor = Palette.Value,
    cursorColor = Palette.Accent,
    focusedContainerColor = Palette.Black,
    unfocusedContainerColor = Palette.Black,
)
