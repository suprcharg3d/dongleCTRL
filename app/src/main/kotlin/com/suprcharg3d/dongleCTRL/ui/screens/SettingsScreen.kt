package com.suprcharg3d.dongleCTRL.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.suprcharg3d.dongleCTRL.data.AppPrefs
import com.suprcharg3d.dongleCTRL.data.ThemeMode
import com.suprcharg3d.dongleCTRL.ui.components.MainTabHeader
import com.suprcharg3d.dongleCTRL.ui.components.SettingActionRow
import com.suprcharg3d.dongleCTRL.ui.components.SettingSwitchRow
import com.suprcharg3d.dongleCTRL.ui.components.SettingsSectionCard
import com.suprcharg3d.dongleCTRL.protocol.DacProfile

@Composable
fun SettingsScreen(
    prefs: AppPrefs,
    catalogProfiles: List<DacProfile>,
    usbLogs: List<String>,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAutoApplyChange: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showCatalogDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp)
    ) {
        MainTabHeader(title = "SETTINGS")

        SettingsSectionCard(title = "Preferences") {
            SettingActionRow(
                icon = Icons.Rounded.DarkMode,
                title = "Theme Mode",
                valueText = prefs.themeMode.label,
                onClick = { showThemeDialog = true }
            )
            SettingSwitchRow(
                icon = Icons.Rounded.AutoMode,
                title = "Instant Live PEQ Sync",
                checked = prefs.autoApplyEq,
                showDivider = false,
                onCheckedChange = onAutoApplyChange
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        SettingsSectionCard(title = "Hardware & Offline Database") {
            SettingActionRow(
                icon = Icons.Rounded.Memory,
                title = "Supported DAC Catalog",
                subtitle = "EPZ · WalkPlay · FiiO",
                valueText = "${catalogProfiles.size}",
                onClick = { showCatalogDialog = true }
            )
            SettingActionRow(
                icon = Icons.Rounded.Terminal,
                title = "Protocol Debug Log",
                subtitle = "Raw packet trace",
                valueText = "${usbLogs.size}",
                onClick = { showLogsDialog = true }
            )
            SettingActionRow(
                icon = Icons.Rounded.Info,
                title = "About dongleCTRL",
                valueText = "v1.1.1",
                showDivider = false,
                onClick = { showAboutDialog = true }
            )
        }
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Theme Mode") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onThemeModeChange(mode)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = prefs.themeMode == mode,
                                onClick = {
                                    onThemeModeChange(mode)
                                    showThemeDialog = false
                                }
                            )
                            Text(mode.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("Close") }
            }
        )
    }

    if (showCatalogDialog) {
        CatalogBrowserDialog(
            profiles = catalogProfiles,
            onDismiss = { showCatalogDialog = false }
        )
    }

    if (showLogsDialog) {
        Dialog(
            onDismissRequest = { showLogsDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.78f),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    Text(
                        text = "Protocol Packets Log",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        if (usbLogs.isEmpty()) {
                            item {
                                Text(
                                    "No packets logged yet.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            items(usbLogs.asReversed()) { line ->
                                Text(
                                    text = line,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(vertical = 3.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onClearLogs,
                            enabled = usbLogs.isNotEmpty()
                        ) {
                            Text("Clear")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = {
                                    val clipboard =
                                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "Protocol Packets Log",
                                            usbLogs.joinToString("\n")
                                        )
                                    )
                                    Toast.makeText(
                                        context,
                                        "Copied ${usbLogs.size} log lines",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                enabled = usbLogs.isNotEmpty()
                            ) {
                                Text("Copy")
                            }
                            TextButton(onClick = { showLogsDialog = false }) {
                                Text("Close")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("dongleCTRL") },
            text = {
                Text(
                    "Multi-protocol USB & Bluetooth DAC/AMP controller and Parametric EQ (PEQ) suite.\n\n" +
                        "Transports:\n" +
                        "• USB HID, USB Serial (CDC) & Bluetooth (SPP / BLE GATT)\n\n" +
                        "Supported engines & chipsets:\n" +
                        "• EPZ / WalkPlay / CB (0x4B) & CB1100AU (0xCB)\n" +
                        "• KTMicro KT02H20, KT0231H & KT1213 (0x57)\n" +
                        "• Conexant CX31993 & GM55\n" +
                        "• FiiO USB HID (V1/V2 & GigaWit bridge), Desktop Serial (K15/K17/K19) & Bluetooth (GAIA V1/V3, Universal, BR13, Q5)\n\n" +
                        "Features:\n" +
                        "• Interactive PEQ, AutoEQ import/export & 10-band presets\n" +
                        "• Hardware controls (Gain, DAC Filter, Balance, Volume, Scene, RGB)\n\n" +
                        "Disclaimer:\n" +
                        "• Provided AS-IS without warranty. You use this app at your own risk; the authors are not responsible for any DAC bricking, corrupted flash/firmware, IEM damage, or hearing injury.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("Done") }
            }
        )
    }
}

@Composable
private fun CatalogBrowserDialog(
    profiles: List<DacProfile>,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, profiles) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) profiles.take(60)
        else profiles.filter {
            it.model.lowercase().contains(q) ||
                it.brand.lowercase().contains(q) ||
                it.protocol.displayName.lowercase().contains(q)
        }.take(60)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supported DACs (${profiles.size})") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search EPZ, FiiO, KA17, TP35...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.id }) { prof ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text("${prof.brand} ${prof.model}", fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "${prof.protocol.displayName} · ${prof.bandCount} bands · VID 0x${"%04X".format(prof.vendorId)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
