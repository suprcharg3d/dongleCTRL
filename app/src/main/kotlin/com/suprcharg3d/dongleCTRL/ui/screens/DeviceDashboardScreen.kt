package com.suprcharg3d.dongleCTRL.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NoiseControlOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suprcharg3d.dongleCTRL.ui.ActiveDacSession
import com.suprcharg3d.dongleCTRL.ui.MainViewModel
import com.suprcharg3d.dongleCTRL.ui.components.ActionConfirmDialog
import com.suprcharg3d.dongleCTRL.ui.components.DacProductImage
import com.suprcharg3d.dongleCTRL.ui.components.DualSliderSwitchRow
import com.suprcharg3d.dongleCTRL.ui.components.InteractivePeqGraph
import com.suprcharg3d.dongleCTRL.ui.components.MultiSelectPickerDialog
import com.suprcharg3d.dongleCTRL.ui.components.OptionPickerDialog
import com.suprcharg3d.dongleCTRL.ui.components.RenameDeviceDialog
import com.suprcharg3d.dongleCTRL.ui.components.SettingActionRow
import com.suprcharg3d.dongleCTRL.ui.components.SettingSliderRow
import com.suprcharg3d.dongleCTRL.ui.components.SettingSwitchRow
import com.suprcharg3d.dongleCTRL.ui.components.SettingsSectionCard
import com.suprcharg3d.dongleCTRL.protocol.ControlCategory
import com.suprcharg3d.dongleCTRL.protocol.ControlKind
import com.suprcharg3d.dongleCTRL.protocol.DacControlDescriptor
import com.suprcharg3d.dongleCTRL.protocol.DacControlValue
import com.suprcharg3d.dongleCTRL.protocol.NamedOption
import kotlin.math.roundToInt

@Composable
fun DeviceDashboardScreen(
    session: ActiveDacSession,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenEqualizer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val caps = session.capabilities
    val st = session.state

    var pickerTitle by remember { mutableStateOf<String?>(null) }
    var pickerOptions by remember { mutableStateOf<List<NamedOption>>(emptyList()) }
    var pickerSelected by remember { mutableStateOf(0) }
    var pickerOnSelect by remember { mutableStateOf<(NamedOption) -> Unit>({}) }
    var isPresetPicker by remember { mutableStateOf(false) }
    var bitmaskPickerCtrl by remember { mutableStateOf<DacControlDescriptor?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var pendingConfirm by remember { mutableStateOf<PendingDeviceConfirm?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp)
    ) {
        // Minimalist top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.profile.model,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${session.profile.brand} · ${session.profile.protocol.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (session.hasUnsavedFlashEdits) {
                FilledTonalButton(onClick = { viewModel.saveToHardwareFlash() }) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Flash")
                }
            } else {
                TextButton(onClick = { viewModel.disconnectSession() }) {
                    Text("Close")
                }
            }
        }

        if (session.isBusy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
        }

        // Hero DAC illustration + crisp white contrast status bar inspired by reference UI
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DacProductImage(
                profile = session.profile,
                isConnected = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(188.dp)
                    .padding(vertical = 4.dp),
                fallbackModifier = Modifier.size(width = 210.dp, height = 116.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                color = Color(0xFFF2F4F7),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "STATUS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF475569)
                    )
                    Text(
                        text = session.statusBanner ?: "CONNECTED",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF0E1013)
                    )
                }
            }
        }

        // --- SOUND SETTINGS ---
        val activePresets = caps.activePresetsForUac(st.uacMode)
        if (caps.hasEqSwitch || activePresets.isNotEmpty() || caps.maxBands > 0) {
            SettingsSectionCard(title = "Sound Settings") {
                if (caps.hasEqSwitch) {
                    SettingSwitchRow(
                        icon = Icons.Rounded.GraphicEq,
                        title = "Hardware EQ",
                        subtitle = when {
                            !st.eqEnabled -> "Hardware EQ off"
                            caps.bypassPresetId != null && st.activePresetId == caps.bypassPresetId ->
                                "Bypass preset active"
                            else -> "Parametric DSP active"
                        },
                        checked = st.eqEnabled,
                        onCheckedChange = viewModel::setEqEnabled
                    )
                }

                if (activePresets.isNotEmpty()) {
                    val resolvedPresets = activePresets.map { p ->
                        val custom = st.customPresetNames[p.id]
                        if (!custom.isNullOrBlank()) p.copy(label = custom) else p
                    }
                    val activeLabel = resolvedPresets.firstOrNull { it.id == st.activePresetId }?.label
                        ?: "Slot ${st.activePresetId}"
                    SettingActionRow(
                        icon = Icons.Rounded.Tune,
                        title = "Hardware Preset",
                        valueText = activeLabel,
                        onClick = {
                            pickerTitle = "Select Hardware Preset"
                            pickerOptions = resolvedPresets
                            pickerSelected = st.activePresetId
                            isPresetPicker = true
                            pickerOnSelect = { viewModel.selectHardwarePreset(it.id) }
                        }
                    )
                }

                if (caps.maxBands > 0) {
                    // Equalizer card with live mini curve preview
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenEqualizer)
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(21.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Parametric Equalizer",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val preampRounded = (st.globalGainDb * 10.0).roundToInt() / 10.0
                                val peqSubtitle = if (caps.hasGlobalGain && preampRounded != 0.0) {
                                    "${st.bands.size} bands · ${caps.sampleRate / 1000} kHz DSP · Preamp ${if (preampRounded > 0) "+" else ""}$preampRounded dB"
                                } else {
                                    "${st.bands.size} bands · ${caps.sampleRate / 1000} kHz DSP · Tap to tune"
                                }
                                Text(
                                    text = peqSubtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = "Edit PEQ",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        InteractivePeqGraph(
                            bands = st.bands,
                            selectedBandIndex = 0,
                            globalGainDb = st.globalGainDb,
                            sampleRate = caps.sampleRate,
                            minGainDb = caps.minGainDb,
                            maxGainDb = caps.maxGainDb,
                            interactive = false
                        )
                    }
                }
            }
        }

        // --- DEVICE CONTROL ---
        val hasDeviceControls = caps.hasDacFilter || caps.hasWorkMode || caps.hasOutputMode ||
            caps.hasVolume || caps.hasChannelBalance || caps.hasLedControl ||
            caps.hasSurround3d || caps.hasUacSwitch

        if (hasDeviceControls) {
            Spacer(modifier = Modifier.height(18.dp))
            SettingsSectionCard(title = "Device Control") {
                if (caps.hasDacFilter && caps.dacFilterOptions.isNotEmpty()) {
                    val label = caps.dacFilterOptions.firstOrNull { it.id == st.dacFilterIndex }?.label ?: "Default"
                    SettingActionRow(
                        icon = Icons.Rounded.Tune,
                        title = "DAC Filter Type",
                        valueText = label,
                        onClick = {
                            pickerTitle = "DAC Digital Filter"
                            pickerOptions = caps.dacFilterOptions
                            pickerSelected = st.dacFilterIndex
                            pickerOnSelect = { viewModel.setDacFilter(it.id) }
                        }
                    )
                }

                if (caps.hasWorkMode && caps.workModeOptions.isNotEmpty()) {
                    val label = caps.workModeOptions.firstOrNull { it.id == st.workModeIndex }?.label ?: "Normal"
                    SettingActionRow(
                        icon = Icons.Rounded.Bolt,
                        title = "Gain Level",
                        valueText = label,
                        onClick = {
                            pickerTitle = "Output Gain Level"
                            pickerOptions = caps.workModeOptions
                            pickerSelected = st.workModeIndex
                            pickerOnSelect = { viewModel.setWorkMode(it.id) }
                        }
                    )
                }

                if (caps.hasOutputMode && caps.outputModeOptions.isNotEmpty()) {
                    val label = caps.outputModeOptions.firstOrNull { it.id == st.outputModeIndex }?.label ?: "Standard"
                    SettingActionRow(
                        icon = Icons.Rounded.Headset,
                        title = "Amp Work Mode",
                        valueText = label,
                        onClick = {
                            pickerTitle = "Amplifier Mode"
                            pickerOptions = caps.outputModeOptions
                            pickerSelected = st.outputModeIndex
                            pickerOnSelect = { viewModel.setOutputMode(it.id) }
                        }
                    )
                }

                if (caps.hasVolume) {
                    SettingSliderRow(
                        icon = Icons.AutoMirrored.Rounded.VolumeUp,
                        title = "Hardware Volume",
                        valueLabel = "${st.volume} / ${st.maxVolume}",
                        value = st.volume.toFloat(),
                        valueRange = 0f..st.maxVolume.toFloat().coerceAtLeast(1f),
                        onValueChange = { viewModel.setVolume(it.roundToInt()) }
                    )
                }

                if (caps.hasChannelBalance) {
                    val balMax = caps.channelBalanceMax.coerceAtLeast(1)
                    val clampedBal = st.channelBalance.coerceIn(-balMax, balMax)
                    val balText = when {
                        clampedBal < 0 -> "L ${-clampedBal} dB"
                        clampedBal > 0 -> "R +$clampedBal dB"
                        else -> "Center (0)"
                    }
                    SettingSliderRow(
                        icon = Icons.Rounded.Balance,
                        title = "Channel Balance",
                        valueLabel = balText,
                        value = clampedBal.toFloat(),
                        valueRange = -balMax.toFloat()..balMax.toFloat(),
                        steps = (balMax * 2 - 1).coerceAtLeast(0),
                        showDivider = caps.hasLedControl || caps.hasSurround3d,
                        onValueChange = { viewModel.setChannelBalance(it.roundToInt()) }
                    )
                }

                if (caps.hasLedControl && caps.ledOptions.isNotEmpty()) {
                    val label = caps.ledOptions.firstOrNull { it.id == st.ledMode }?.label ?: "On"
                    SettingActionRow(
                        icon = Icons.Rounded.Lightbulb,
                        title = "Indicator LED",
                        valueText = label,
                        showDivider = caps.hasSurround3d || caps.hasUacSwitch,
                        onClick = {
                            pickerTitle = "Indicator LED Mode"
                            pickerOptions = caps.ledOptions
                            pickerSelected = st.ledMode
                            pickerOnSelect = { viewModel.setLedMode(it.id) }
                        }
                    )
                }

                if (caps.hasUacSwitch && caps.uacOptions.isNotEmpty()) {
                    val label = caps.uacOptions.firstOrNull { it.id == st.uacMode }?.label ?: "UAC ${st.uacMode + 1}.0"
                    SettingActionRow(
                        icon = Icons.Rounded.Tune,
                        title = "USB Audio Class (UAC)",
                        valueText = label,
                        showDivider = caps.hasSurround3d,
                        onClick = {
                            pickerTitle = "USB Audio Class Mode"
                            pickerOptions = caps.uacOptions
                            pickerSelected = st.uacMode
                            pickerOnSelect = { viewModel.setUacMode(it.id) }
                        }
                    )
                }

                if (caps.hasSurround3d) {
                    SettingSwitchRow(
                        icon = Icons.Rounded.SurroundSound,
                        title = "3D Spatial Surround",
                        checked = st.surround3dEnabled,
                        showDivider = false,
                        onCheckedChange = viewModel::setSurround3d
                    )
                }
            }
        }

        // --- MICROPHONE ---
        if (caps.hasMicGain || caps.hasMicMonitor || caps.hasMicEnc || caps.hasMicSwitch) {
            Spacer(modifier = Modifier.height(18.dp))
            SettingsSectionCard(title = "Microphone") {
                if (caps.hasMicSwitch) {
                    SettingSwitchRow(
                        icon = Icons.Rounded.Mic,
                        title = "Inline Microphone",
                        subtitle = if (st.micSwitchEnabled) "CTIA / OMTP microphone active" else "Disabled",
                        checked = st.micSwitchEnabled,
                        showDivider = caps.hasMicGain || caps.hasMicMonitor || caps.hasMicEnc,
                        onCheckedChange = viewModel::setMicSwitch
                    )
                }
                if (caps.hasMicGain) {
                    SettingSliderRow(
                        icon = Icons.Rounded.Mic,
                        title = "Microphone Gain",
                        valueLabel = "${st.micGainDb} dB",
                        value = st.micGainDb.toFloat(),
                        valueRange = caps.micGainRange.first.toFloat()..caps.micGainRange.last.toFloat(),
                        showDivider = caps.hasMicMonitor || caps.hasMicEnc,
                        onValueChange = { viewModel.setMicGain(it.roundToInt()) }
                    )
                }
                if (caps.hasMicMonitor) {
                    SettingSwitchRow(
                        icon = Icons.Rounded.Headset,
                        title = "Sidetone / Mic Monitor",
                        checked = st.micMonitorEnabled,
                        showDivider = caps.hasMicEnc,
                        onCheckedChange = viewModel::setMicMonitor
                    )
                }
                if (caps.hasMicEnc) {
                    SettingSwitchRow(
                        icon = Icons.Rounded.NoiseControlOff,
                        title = "ENC Noise Reduction",
                        checked = st.micEncEnabled,
                        showDivider = false,
                        onCheckedChange = viewModel::setMicEnc
                    )
                }
            }
        }

        // --- SCHEMA-DRIVEN EXTRA CONTROLS BY CATEGORY ---
        val groupedControls = remember(caps.extraControls) {
            ControlCategory.entries.mapNotNull { category ->
                val items = caps.extraControls.filter { it.category == category }
                if (items.isEmpty()) null else category to items
            }
        }

        groupedControls.forEach { (category, controls) ->
            Spacer(modifier = Modifier.height(18.dp))
            SettingsSectionCard(title = category.title) {
                controls.forEachIndexed { index, ctrl ->
                    val showDivider = index < controls.lastIndex
                    val value = st.extraControlValues[ctrl.key] ?: DacControlValue(intValue = ctrl.min)
                    val icon = iconForExtraControl(ctrl)
                    when (ctrl.kind) {
                        ControlKind.SWITCH -> {
                            SettingSwitchRow(
                                icon = icon,
                                title = ctrl.label,
                                checked = value.boolValue,
                                showDivider = showDivider,
                                onCheckedChange = { checked ->
                                    viewModel.setExtraControl(
                                        ctrl.key,
                                        value.copy(boolValue = checked, intValue = if (checked) 1 else 0)
                                    )
                                }
                            )
                        }
                        ControlKind.SLIDER -> {
                            val effectiveMax = (value.dynamicMax ?: ctrl.max).coerceAtLeast(ctrl.min + 1)
                            val step = ctrl.step.coerceAtLeast(1)
                            val intervals = ((effectiveMax - ctrl.min) / step).coerceAtLeast(1)
                            val steps = if (step > 1 && intervals > 1) intervals - 1 else 0
                            val clamped = value.intValue.coerceIn(ctrl.min, effectiveMax)
                            SettingSliderRow(
                                icon = icon,
                                title = ctrl.label,
                                valueLabel = formatExtraSliderValue(ctrl, clamped),
                                value = clamped.toFloat(),
                                valueRange = ctrl.min.toFloat()..effectiveMax.toFloat(),
                                steps = steps,
                                showDivider = showDivider,
                                onValueChange = { raw ->
                                    val snapped = (ctrl.min + (((raw - ctrl.min) / step).roundToInt() * step))
                                        .coerceIn(ctrl.min, effectiveMax)
                                    viewModel.setExtraControl(ctrl.key, value.copy(intValue = snapped))
                                }
                            )
                        }
                        ControlKind.SINGLE_CHOICE -> {
                            val currentLabel = ctrl.options.firstOrNull { it.id == value.intValue }?.label
                                ?: ctrl.options.firstOrNull()?.label
                                ?: value.intValue.toString()
                            SettingActionRow(
                                icon = icon,
                                title = ctrl.label,
                                valueText = currentLabel,
                                showDivider = showDivider,
                                onClick = {
                                    pickerTitle = ctrl.label
                                    pickerOptions = ctrl.options
                                    pickerSelected = value.intValue
                                    pickerOnSelect = { opt ->
                                        viewModel.setExtraControl(ctrl.key, value.copy(intValue = opt.id))
                                    }
                                }
                            )
                        }
                        ControlKind.BITMASK -> {
                            val selectedLabels = ctrl.options
                                .filter { (value.intValue and it.id) != 0 }
                                .joinToString(", ") { it.label }
                            SettingActionRow(
                                icon = icon,
                                title = ctrl.label,
                                valueText = selectedLabels.ifEmpty { "None" },
                                showDivider = showDivider,
                                onClick = { bitmaskPickerCtrl = ctrl }
                            )
                        }
                        ControlKind.DUAL_SLIDER_SWITCH -> {
                            DualSliderSwitchRow(
                                icon = icon,
                                title = ctrl.label,
                                value = value,
                                sliders = ctrl.sliders,
                                showDivider = showDivider,
                                onValueChange = { updated ->
                                    viewModel.setExtraControl(ctrl.key, updated)
                                }
                            )
                        }
                        ControlKind.ACTION -> {
                            SettingActionRow(
                                icon = icon,
                                title = ctrl.label,
                                valueText = "Run",
                                showDivider = showDivider,
                                onClick = {
                                    viewModel.setExtraControl(ctrl.key, value.copy(intValue = 1))
                                }
                            )
                        }
                    }
                }
            }
        }

        // --- DEVICE MANAGEMENT (RENAME, CLEAR PAIRING, RESET, SHUTDOWN) ---
        val hasManagement = caps.canRenameDevice ||
            caps.clearPairingCmd > 0 ||
            caps.resetDefaultCmd > 0 ||
            caps.btResetDefaultCmd > 0 ||
            caps.systemStandbyCmd > 0 ||
            caps.closeDeviceCmd > 0

        if (hasManagement) {
            Spacer(modifier = Modifier.height(18.dp))
            SettingsSectionCard(title = "Device Management") {
                val actions = buildList {
                    if (caps.canRenameDevice) {
                        add("rename")
                    }
                    if (caps.clearPairingCmd > 0) {
                        add("clear_pairing")
                    }
                    if (caps.resetDefaultCmd > 0) {
                        add("reset_default")
                    }
                    if (caps.btResetDefaultCmd > 0) {
                        add("bt_reset")
                    }
                    if (caps.systemStandbyCmd > 0) {
                        add("standby")
                    }
                    if (caps.closeDeviceCmd > 0) {
                        add("close_device")
                    }
                }
                actions.forEachIndexed { idx, action ->
                    val showDivider = idx < actions.lastIndex
                    when (action) {
                        "rename" -> {
                            SettingActionRow(
                                icon = Icons.Rounded.Edit,
                                title = "Device Name",
                                valueText = st.deviceName?.ifBlank { null } ?: session.profile.model,
                                showDivider = showDivider,
                                onClick = { showRenameDialog = true }
                            )
                        }
                        "clear_pairing" -> {
                            SettingActionRow(
                                icon = Icons.Rounded.BluetoothDisabled,
                                title = "Clear Paired Devices",
                                valueText = "Reset pairing list",
                                showDivider = showDivider,
                                onClick = {
                                    pendingConfirm = PendingDeviceConfirm(
                                        title = "Clear Paired Devices",
                                        message = "Remove all Bluetooth pairing records stored on ${session.profile.model}?",
                                        confirmLabel = "Clear Pairing"
                                    ) {
                                        viewModel.sendDeviceAction(caps.clearPairingCmd, "Cleared paired devices")
                                    }
                                }
                            )
                        }
                        "reset_default" -> {
                            SettingActionRow(
                                icon = Icons.Rounded.RestartAlt,
                                title = "Restore Default Settings",
                                valueText = "Factory reset",
                                showDivider = showDivider,
                                onClick = {
                                    pendingConfirm = PendingDeviceConfirm(
                                        title = "Restore Defaults",
                                        message = "Reset device audio, display, and control settings to factory defaults?",
                                        confirmLabel = "Restore"
                                    ) {
                                        viewModel.sendDeviceAction(caps.resetDefaultCmd, "Restored default settings")
                                    }
                                }
                            )
                        }
                        "bt_reset" -> {
                            SettingActionRow(
                                icon = Icons.Rounded.RestartAlt,
                                title = "Reset Bluetooth Settings",
                                valueText = "Reset BT module",
                                showDivider = showDivider,
                                onClick = {
                                    pendingConfirm = PendingDeviceConfirm(
                                        title = "Reset Bluetooth",
                                        message = "Reset Bluetooth subsystem settings on ${session.profile.model}?",
                                        confirmLabel = "Reset"
                                    ) {
                                        viewModel.sendDeviceAction(caps.btResetDefaultCmd, "Bluetooth reset sent")
                                    }
                                }
                            )
                        }
                        "standby" -> {
                            SettingActionRow(
                                icon = Icons.Rounded.Timer,
                                title = "Standby Mode",
                                valueText = "Sleep now",
                                showDivider = showDivider,
                                onClick = {
                                    pendingConfirm = PendingDeviceConfirm(
                                        title = "Enter Standby",
                                        message = "Put ${session.profile.model} into standby mode?",
                                        confirmLabel = "Standby"
                                    ) {
                                        viewModel.sendDeviceAction(caps.systemStandbyCmd, "Standby command sent")
                                    }
                                }
                            )
                        }
                        "close_device" -> {
                            SettingActionRow(
                                icon = Icons.Rounded.PowerSettingsNew,
                                title = "Shut Down Device",
                                valueText = "Power off",
                                showDivider = showDivider,
                                onClick = {
                                    pendingConfirm = PendingDeviceConfirm(
                                        title = "Shut Down ${session.profile.model}",
                                        message = "Power off the device and disconnect this session?",
                                        confirmLabel = "Shut Down"
                                    ) {
                                        viewModel.sendDeviceAction(
                                            cmdId = caps.closeDeviceCmd,
                                            label = "Shutting down device",
                                            disconnectAfter = true
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    pickerTitle?.let { title ->
        val displayedOptions = if (isPresetPicker) {
            caps.activePresetsForUac(st.uacMode).map { p ->
                val custom = st.customPresetNames[p.id]
                if (!custom.isNullOrBlank()) p.copy(label = custom) else p
            }
        } else {
            pickerOptions
        }
        OptionPickerDialog(
            title = title,
            options = displayedOptions,
            selectedId = if (isPresetPicker) st.activePresetId else pickerSelected,
            onSelect = pickerOnSelect,
            onDismiss = {
                pickerTitle = null
                isPresetPicker = false
            },
            categoryById = if (isPresetPicker) caps.presetCategories else emptyMap(),
            editableIds = if (isPresetPicker) caps.writablePresetIds else emptySet(),
            nameMaxChars = caps.presetNameMaxChars,
            onRenameOption = if (isPresetPicker && caps.canRenamePresets) {
                { opt, newName -> viewModel.setPresetName(opt.id, newName) }
            } else null
        )
    }

    bitmaskPickerCtrl?.let { ctrl ->
        val value = st.extraControlValues[ctrl.key] ?: DacControlValue()
        MultiSelectPickerDialog(
            title = ctrl.label,
            options = ctrl.options,
            initialMask = value.intValue,
            supportMask = value.supportMask,
            onSave = { mask ->
                viewModel.setExtraControl(ctrl.key, value.copy(intValue = mask))
            },
            onDismiss = { bitmaskPickerCtrl = null }
        )
    }

    if (showRenameDialog) {
        RenameDeviceDialog(
            currentName = st.deviceName?.ifBlank { null } ?: session.profile.model,
            maxBytes = caps.deviceNameMaxBytes,
            onSave = { newName -> viewModel.setDeviceName(newName) },
            onDismiss = { showRenameDialog = false }
        )
    }

    pendingConfirm?.let { confirm ->
        ActionConfirmDialog(
            title = confirm.title,
            message = confirm.message,
            confirmLabel = confirm.confirmLabel,
            onConfirm = confirm.onConfirm,
            onDismiss = { pendingConfirm = null }
        )
    }
}

private data class PendingDeviceConfirm(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit
)

private fun formatExtraSliderValue(ctrl: DacControlDescriptor, value: Int): String {
    if (value == 0 && ctrl.startText.isNotEmpty()) {
        return ctrl.startText
    }
    return if (ctrl.unit.isNotEmpty()) "$value ${ctrl.unit}" else value.toString()
}

private fun iconForExtraControl(ctrl: DacControlDescriptor): ImageVector = when {
    ctrl.key.contains("battery") || ctrl.key.contains("charge") || ctrl.key.contains("power") ->
        Icons.Rounded.BatteryChargingFull
    ctrl.key.contains("bright") -> Icons.Rounded.Brightness6
    ctrl.key.contains("rotate") || ctrl.key.contains("screen_rotate") -> Icons.Rounded.ScreenRotation
    ctrl.key.contains("screen") || ctrl.key.contains("timeout") -> Icons.Rounded.Timer
    ctrl.key.contains("bt") || ctrl.key.contains("double_link") || ctrl.key.contains("dual") ->
        Icons.Rounded.Bluetooth
    ctrl.key.contains("car") -> Icons.Rounded.DirectionsCar
    ctrl.key.contains("vol") || ctrl.key.contains("hfp") || ctrl.key.contains("tip") ->
        Icons.AutoMirrored.Rounded.VolumeUp
    ctrl.key.contains("distortion") || ctrl.key.contains("eq") || ctrl.key.contains("dre") ->
        Icons.Rounded.GraphicEq
    ctrl.category == ControlCategory.BUTTONS -> Icons.Rounded.TouchApp
    ctrl.category == ControlCategory.DISPLAY -> Icons.Rounded.Lightbulb
    ctrl.category == ControlCategory.AUDIO -> Icons.Rounded.Tune
    else -> Icons.Rounded.Settings
}
