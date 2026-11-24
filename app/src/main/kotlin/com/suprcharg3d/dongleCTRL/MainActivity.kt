package com.suprcharg3d.dongleCTRL

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.suprcharg3d.dongleCTRL.dsp.EqPreset
import com.suprcharg3d.dongleCTRL.ui.AppScreen
import com.suprcharg3d.dongleCTRL.ui.MainViewModel
import com.suprcharg3d.dongleCTRL.ui.components.BandSelectionDialog
import com.suprcharg3d.dongleCTRL.ui.components.CustomSlotSelectionDialog
import com.suprcharg3d.dongleCTRL.ui.components.TargetDacSelectionDialog
import com.suprcharg3d.dongleCTRL.ui.screens.DeviceDashboardScreen
import com.suprcharg3d.dongleCTRL.ui.screens.DevicesScreen
import com.suprcharg3d.dongleCTRL.ui.screens.EqualizerScreen
import com.suprcharg3d.dongleCTRL.ui.screens.PresetsScreen
import com.suprcharg3d.dongleCTRL.ui.screens.SettingsScreen
import com.suprcharg3d.dongleCTRL.ui.theme.DongleCtrlTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }

            var pendingExportPreset by remember { mutableStateOf<EqPreset?>(null) }

            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (text != null) {
                        val name = resolveDisplayName(uri)
                        viewModel.importAutoEqToPresets(name, text)
                    }
                }
            }

            val exportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("text/plain")
            ) { uri ->
                val targetPreset = pendingExportPreset
                pendingExportPreset = null
                if (uri != null && targetPreset != null) {
                    val content = viewModel.exportPresetAutoEqText(targetPreset)
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                }
            }

            val btPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                viewModel.onBluetoothPermissionResult()
            }

            val requestBtPermissions: () -> Unit = {
                val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                } else {
                    arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
                }
                btPermissionLauncher.launch(perms)
            }

            LaunchedEffect(uiState.toastMessage) {
                val msg = uiState.toastMessage ?: return@LaunchedEffect
                snackbarHostState.showSnackbar(msg)
                viewModel.clearToast()
            }

            BackHandler(
                enabled = uiState.currentScreen == AppScreen.EQUALIZER ||
                    uiState.currentScreen == AppScreen.DASHBOARD
            ) {
                when (uiState.currentScreen) {
                    AppScreen.EQUALIZER -> viewModel.navigateTo(AppScreen.DASHBOARD)
                    AppScreen.DASHBOARD -> viewModel.navigateTo(AppScreen.DEVICES)
                    else -> Unit
                }
            }

            DongleCtrlTheme(themeMode = uiState.prefs.themeMode) {
                val showBottomBar = uiState.currentScreen == AppScreen.DEVICES ||
                    uiState.currentScreen == AppScreen.PRESETS ||
                    uiState.currentScreen == AppScreen.SETTINGS

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        if (showBottomBar) {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = uiState.currentScreen == AppScreen.DEVICES,
                                    onClick = { viewModel.navigateTo(AppScreen.DEVICES) },
                                    icon = { Icon(Icons.Rounded.Headphones, contentDescription = "Devices") },
                                    label = { Text("Devices") }
                                )
                                NavigationBarItem(
                                    selected = uiState.currentScreen == AppScreen.PRESETS,
                                    onClick = { viewModel.navigateTo(AppScreen.PRESETS) },
                                    icon = { Icon(Icons.Rounded.Bookmarks, contentDescription = "Presets") },
                                    label = { Text("Presets") }
                                )
                                NavigationBarItem(
                                    selected = uiState.currentScreen == AppScreen.SETTINGS,
                                    onClick = { viewModel.navigateTo(AppScreen.SETTINGS) },
                                    icon = { Icon(Icons.Rounded.Settings, contentDescription = "Settings") },
                                    label = { Text("Settings") }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (uiState.currentScreen) {
                            AppScreen.DEVICES -> DevicesScreen(
                                detectedDevices = uiState.detectedDevices,
                                detectedBtDevices = uiState.detectedBtDevices,
                                hasBtPermission = uiState.hasBtPermission,
                                isBtScanning = uiState.isBtScanning,
                                activeSession = uiState.activeSession,
                                catalogProfiles = uiState.catalogProfiles,
                                onRefreshScan = viewModel::refreshUsbScan,
                                onScanBluetooth = {
                                    if (!uiState.hasBtPermission) {
                                        requestBtPermissions()
                                    } else {
                                        viewModel.refreshBluetoothScan()
                                    }
                                },
                                onRequestBtPermission = requestBtPermissions,
                                onConnectUsb = { viewModel.connectToUsbDevice(it, navigateToDashboard = true) },
                                onConnectBt = { viewModel.connectToBluetoothDevice(it, navigateToDashboard = true) },
                                onOpenActiveSession = { viewModel.navigateTo(AppScreen.DASHBOARD) },
                                onOverrideProtocol = viewModel::overrideProtocol
                            )

                            AppScreen.PRESETS -> {
                                val hasTargetDac = uiState.activeSession != null ||
                                    uiState.detectedBtDevices.isNotEmpty() ||
                                    uiState.detectedDevices.any { it.isSupportedCandidate }
                                PresetsScreen(
                                    presets = uiState.presets,
                                    hasTargetDac = hasTargetDac,
                                    onApplyPreset = { preset -> viewModel.applyPreset(preset) },
                                    onSavePreset = viewModel::saveOrUpdateUserPreset,
                                    onDeletePreset = viewModel::deleteUserPreset,
                                    onRequestImportFile = {
                                        importLauncher.launch(arrayOf("text/*", "application/xml", "*/*"))
                                    },
                                    onRequestExportPreset = { preset ->
                                        pendingExportPreset = preset
                                        val slug = preset.name
                                            .lowercase()
                                            .replace(Regex("[^a-z0-9]+"), "_")
                                            .trim('_')
                                            .ifEmpty { "peq_preset" }
                                        exportLauncher.launch("${slug}.txt")
                                    }
                                )
                            }

                            AppScreen.DASHBOARD -> {
                                val session = uiState.activeSession
                                if (session == null) {
                                    viewModel.navigateTo(AppScreen.DEVICES)
                                } else {
                                    DeviceDashboardScreen(
                                        session = session,
                                        viewModel = viewModel,
                                        onBack = { viewModel.navigateTo(AppScreen.DEVICES) },
                                        onOpenEqualizer = { viewModel.navigateTo(AppScreen.EQUALIZER) }
                                    )
                                }
                            }

                            AppScreen.EQUALIZER -> {
                                val session = uiState.activeSession
                                if (session == null) {
                                    viewModel.navigateTo(AppScreen.DEVICES)
                                } else {
                                    EqualizerScreen(
                                        session = session,
                                        selectedBandIndex = uiState.selectedBandIndex,
                                        presets = uiState.presets,
                                        autoApplyEnabled = uiState.prefs.autoApplyEq,
                                        viewModel = viewModel,
                                        onBack = { viewModel.navigateTo(AppScreen.DASHBOARD) }
                                    )
                                }
                            }

                            AppScreen.SETTINGS -> SettingsScreen(
                                prefs = uiState.prefs,
                                catalogProfiles = uiState.catalogProfiles,
                                usbLogs = uiState.usbLogs,
                                onThemeModeChange = viewModel::setThemeMode,
                                onAutoApplyChange = viewModel::setAutoApplyEq,
                                onAutoOpenUsbChange = viewModel::setAutoOpenOnUsb,
                                onClearLogs = viewModel::clearUsbLogs
                            )
                        }
                    }
                }

                uiState.pendingTargetDacSelection?.let { pendingDac ->
                    TargetDacSelectionDialog(
                        pending = pendingDac,
                        onSelect = viewModel::confirmTargetDacSelection,
                        onDismiss = viewModel::dismissTargetDacSelection
                    )
                }

                uiState.pendingSlotSelection?.let { pendingSlot ->
                    CustomSlotSelectionDialog(
                        pending = pendingSlot,
                        onSelectSlot = viewModel::confirmSlotSelection,
                        onDismiss = viewModel::dismissSlotSelection
                    )
                }

                uiState.pendingBandSelection?.let { pending ->
                    BandSelectionDialog(
                        pending = pending,
                        onConfirm = viewModel::confirmPendingBandSelection,
                        onDismiss = viewModel::dismissPendingBandSelection
                    )
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        val rawName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            } ?: uri.lastPathSegment ?: "Imported EQ"
        return rawName
            .substringAfterLast('/')
            .replace(Regex("\\.(txt|xml)$", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ')
            .trim()
            .ifEmpty { "Imported EQ" }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.refreshUsbScan()
    }
}
