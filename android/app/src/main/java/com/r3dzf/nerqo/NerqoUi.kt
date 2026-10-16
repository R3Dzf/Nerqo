package com.r3dzf.nerqo

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable internal fun NerqoTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFA8EBD0), onPrimary = Color(0xFF163D35),
        background = Color(0xFF17272C), surface = Color(0xFF20363B),
        surfaceVariant = Color(0xFF2B464A), onSurface = Color(0xFFEEF7F3),
        onBackground = Color(0xFFEEF7F3), onSurfaceVariant = Color(0xFFAEC7C2),
        outlineVariant = Color(0xFF3C565A), secondaryContainer = Color(0xFF32534F),
        onSecondaryContainer = Color(0xFFC1F5DF)
    ) else lightColorScheme(
        primary = Color(0xFF176C5B), onPrimary = Color.White,
        background = Color(0xFFF3F6F5), surface = Color.White,
        surfaceVariant = Color(0xFFEAF1EE), onSurface = Color(0xFF173638),
        onBackground = Color(0xFF173638), onSurfaceVariant = Color(0xFF5B7375),
        outlineVariant = Color(0xFFD7E4DE), secondaryContainer = Color(0xFFD8EFE4),
        onSecondaryContainer = Color(0xFF164D43)
    )
    MaterialTheme(colorScheme = scheme, shapes = Shapes(medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(22.dp)), content = content)
}

@Composable private fun Symbol(@DrawableRes icon: Int, description: String? = null, modifier: Modifier = Modifier.size(24.dp)) {
    Icon(painterResource(icon), contentDescription = description, modifier = modifier)
}
@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun Title(text: String) { Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
@Composable private fun Caption(text: String) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 20.sp) }
private fun bytes(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1048576 -> String.format(Locale.ROOT, "%.1f KB", size / 1024.0)
    size < 1073741824 -> String.format(Locale.ROOT, "%.1f MB", size / 1048576.0)
    else -> String.format(Locale.ROOT, "%.2f GB", size / 1073741824.0)
}

@Composable internal fun NerqoScreen(
    deviceName: String?, connected: Boolean, paired: Boolean, nearby: List<Device>, entries: List<SharedEntry>,
    busy: Boolean, progress: Float, message: String, network: String, hotspotText: String,
    shareText: String, manualIp: String, darkMode: Boolean,
    onShareText: (String) -> Unit, onManualIp: (String) -> Unit, onTheme: (Boolean) -> Unit,
    onScanQr: () -> Unit, onDiscover: () -> Unit, onSelectDevice: (Device) -> Unit,
    onManualConnect: () -> Unit, onRefresh: () -> Unit, onFiles: () -> Unit, onFolder: () -> Unit,
    onSendText: () -> Unit, onPaste: () -> Unit, onEntry: (SharedEntry) -> Unit,
    onHotspot: () -> Unit, onWifiSettings: () -> Unit, onUpdates: () -> Unit
) {
    var page by rememberSaveable { mutableIntStateOf(if (paired) 0 else 1) }
    var manual by rememberSaveable { mutableStateOf(false) }
    val labels = listOf("Transfers", "Devices", "Settings")
    val icons = listOf(R.drawable.ic_transfer, R.drawable.ic_devices, R.drawable.ic_settings)
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                labels.forEachIndexed { index, label -> NavigationBarItem(
                    selected = page == index, onClick = { page = index },
                    icon = { Symbol(icons[index]) }, label = { Text(label, fontSize = 12.sp) }) }
            }
        }
    ) { inset ->
        LazyColumn(Modifier.fillMaxSize().padding(inset).imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_launcher), "Nerqo", Modifier.size(44.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Nerqo", fontWeight = FontWeight.SemiBold, fontSize = 27.sp, modifier = Modifier.weight(1f))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50)) {
                        Text(if (connected) "Connected" else "Local sharing", color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(labels[page], fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Caption(when (page) { 0 -> "From here to there. All on your network."; 1 -> "A secure connection starts here."; else -> "Make yourself at home." })
                }
            }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(message, fontSize = 13.sp, lineHeight = 19.sp)
                        if (busy) {
                            if (progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            when (page) {
                0 -> {
                    item {
                        Surface(color = Color(0xFF16494D), shape = RoundedCornerShape(22.dp)) {
                            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(if (connected) "Ready for your next move." else "Bring your devices closer.", color = Color(0xFFF2FFFA), fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (connected) "Sharing with ${deviceName ?: "Windows"}" else "Pair your computer to start sharing files, folders and ideas.", color = Color(0xFFB8D7D0), fontSize = 13.sp)
                                if (!connected) Button(onClick = { page = 1 }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB5F5DA), contentColor = Color(0xFF123D43))) { Text("Connect a device") }
                            }
                        }
                    }
                    item { Panel {
                        Title("Send something")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onFiles, enabled = !busy && paired, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Symbol(R.drawable.ic_file); Spacer(Modifier.width(8.dp)); Text("Files") }
                            OutlinedButton(onClick = onFolder, enabled = !busy && paired, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Symbol(R.drawable.ic_folder); Spacer(Modifier.width(8.dp)); Text("Folder") }
                        }
                        Caption("Folders travel directly, including subfolders and empty folders.")
                    } }
                    item { Panel {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("A note or a link", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = onPaste, enabled = !busy) { Text("Paste") }
                        }
                        OutlinedTextField(value = shareText, onValueChange = onShareText, placeholder = { Text("What's worth sharing?") }, label = { Text("Text or link") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                        Button(onClick = onSendText, enabled = !busy && paired && shareText.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Send to Windows"); Spacer(Modifier.width(10.dp)); Symbol(R.drawable.ic_arrow) }
                    } }
                    item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Activity", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = onRefresh, enabled = !busy && paired) { Text("Refresh") }
                    } }
                    if (entries.isEmpty()) item { Panel { Symbol(R.drawable.ic_transfer); Title("Nothing shared yet"); Caption("Your files and notes will appear here. Send a little something to get started.") } }
                    // The API already returns newest first. UUID order is not chronological.
                    items(entries.take(150), key = { it.id }) { entry ->
                        Panel {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Box(Modifier.padding(12.dp)) { Symbol(when(entry.kind) { "file" -> R.drawable.ic_file; "folder" -> R.drawable.ic_folder; else -> R.drawable.ic_text }) }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(entry.content, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    Caption(entry.sender + " · " + if (entry.kind in listOf("file", "folder")) bytes(entry.size) else "Text / link")
                                }
                            }
                            OutlinedButton(onClick = { onEntry(entry) }, enabled = !busy && paired, modifier = Modifier.fillMaxWidth()) {
                                Text(when(entry.kind) { "file" -> "Save file"; "folder" -> "Save / retry folder"; else -> "Copy text" })
                            }
                        }
                    }
                }
                1 -> {
                    item { Panel {
                        Title(if (connected) deviceName ?: "Your computer" else "Meet your computer.")
                        Caption(if (connected) "Your pinned security identity is verified." else "Open Nerqo on Windows and scan the QR in Devices. Both devices should use the same Wi-Fi or hotspot.")
                        Button(onClick = onScanQr, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Symbol(R.drawable.ic_qr); Spacer(Modifier.width(10.dp)); Text("Scan pairing QR") }
                        OutlinedButton(onClick = onDiscover, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Find nearby computers") }
                        if (paired) TextButton(onClick = { onRefresh(); page = 0 }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Open transfers") }
                    } }
                    if (nearby.isNotEmpty()) item { Text("Nearby", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
                    items(nearby, key = { "${it.address}:${it.port}" }) { found -> Panel {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Symbol(R.drawable.ic_devices)
                            Column(Modifier.weight(1f)) { Text(found.name, fontWeight = FontWeight.SemiBold); Caption(found.address) }
                            TextButton(onClick = { onSelectDevice(found) }, enabled = !busy) { Text("Connect") }
                        }
                    } }
                    item { Panel {
                        Title("Can't see your computer?")
                        Caption("Check the Wi-Fi connection and allow Nerqo in Windows Firewall. You can also enter the private address shown on Windows.")
                        TextButton(onClick = { manual = !manual }) { Text(if (manual) "Hide manual connection" else "Connect with IP address") }
                        if (manual) {
                            OutlinedTextField(value = manualIp, onValueChange = onManualIp, label = { Text("Local Windows IP") }, placeholder = { Text("192.168.1.5") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Button(onClick = onManualConnect, enabled = !busy && manualIp.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Connect") }
                        }
                    } }
                }
                else -> {
                    item { Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Title("Appearance"); Caption("Use a softer dark palette.") }
                            Switch(checked = darkMode, onCheckedChange = onTheme, modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "Dark appearance" })
                        }
                    } }
                    item { Panel {
                        Title("Your local network")
                        Text(network, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Caption("Prefer 5 GHz or 6 GHz when supported. Your router or hotspot controls the band.")
                        OutlinedButton(onClick = onWifiSettings, modifier = Modifier.fillMaxWidth()) { Text("Open Wi-Fi settings") }
                    } }
                    item { Panel {
                        Title("No router? No problem.")
                        Caption("Create an offline hotspot and join it on Windows. Android chooses the available band.")
                        OutlinedButton(onClick = onHotspot, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Create offline hotspot") }
                        if (hotspotText.isNotBlank()) Text(hotspotText, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                    } }
                    item { Panel {
                        Title("Keep Nerqo up to date")
                        Caption("Updates use the internet. Your local transfers work offline.")
                        Button(onClick = onUpdates, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Check for app updates") }
                        Caption("Nerqo 1.0.1 · Windows + Android")
                    } }
                }
            }
        }
    }
}
