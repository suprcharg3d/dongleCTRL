package com.suprcharg3d.dongleCTRL.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suprcharg3d.dongleCTRL.DongleCtrlApp
import com.suprcharg3d.dongleCTRL.bt.DetectedBluetoothDac
import com.suprcharg3d.dongleCTRL.data.AppPrefs
import com.suprcharg3d.dongleCTRL.data.ThemeMode
import com.suprcharg3d.dongleCTRL.usb.DetectedUsbDac
import com.suprcharg3d.dongleCTRL.dsp.DefaultBands
import com.suprcharg3d.dongleCTRL.dsp.EqBand
import com.suprcharg3d.dongleCTRL.dsp.EqPreset
import com.suprcharg3d.dongleCTRL.dsp.FilterType
import com.suprcharg3d.dongleCTRL.dsp.PeqTextParser
import com.suprcharg3d.dongleCTRL.dsp.PresetBandHelper
import com.suprcharg3d.dongleCTRL.protocol.DacCapabilities
import com.suprcharg3d.dongleCTRL.protocol.DacControlValue
import com.suprcharg3d.dongleCTRL.protocol.DacDeviceState
import com.suprcharg3d.dongleCTRL.protocol.DacProfile
import com.suprcharg3d.dongleCTRL.protocol.DacProtocol
import com.suprcharg3d.dongleCTRL.protocol.DacTransport
import com.suprcharg3d.dongleCTRL.protocol.NamedOption
import com.suprcharg3d.dongleCTRL.protocol.ProtocolFamily
import com.suprcharg3d.dongleCTRL.protocol.ProtocolRegistry
import com.suprcharg3d.dongleCTRL.protocol.TransportType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AppScreen {
    DEVICES,
    PRESETS,
    DASHBOARD,
    EQUALIZER,
    SETTINGS
}

sealed interface TargetDacOption {
    val deviceKey: String
    val title: String
    val subtitle: String
    val isActiveSession: Boolean

    data class ActiveSessionOnly(
        override val deviceKey: String,
        override val title: String,
        override val subtitle: String
    ) : TargetDacOption {
        override val isActiveSession: Boolean = true
    }

    data class UsbTarget(
        val entry: DetectedUsbDac,
        override val isActiveSession: Boolean
    ) : TargetDacOption {
        override val deviceKey: String = entry.device.deviceName
        override val title: String = entry.effectiveProfile.model
        override val subtitle: String = buildString {
            append("USB · ")
            append(entry.effectiveProfile.protocol.displayName)
            if (isActiveSession) append(" · Connected")
        }
    }

    data class BtTarget(
        val entry: DetectedBluetoothDac,
        override val isActiveSession: Boolean
    ) : TargetDacOption {
        override val deviceKey: String = entry.descriptor.deviceKey
        override val title: String = entry.displayName
        override val subtitle: String = buildString {
            append("Bluetooth · ")
            append(entry.matchedProfile.protocol.displayName)
            if (isActiveSession) append(" · Connected")
        }
    }
}

data class PendingTargetDacSelection(
    val preset: EqPreset,
    val options: List<TargetDacOption>
)

data class PendingSlotSelection(
    val preset: EqPreset,
    val dacName: String,
    val slots: List<NamedOption>,
    val customNames: Map<Int, String>,
    val currentPresetId: Int
)

sealed interface PendingBandSelection {
    val candidateBands: List<EqBand>
    val maxSelectable: Int

    data class ApplyPresetToDac(
        val preset: EqPreset,
        val targetSlotId: Int?,
        val targetDacBands: Int,
        val dacName: String,
        override val candidateBands: List<EqBand>,
        override val maxSelectable: Int
    ) : PendingBandSelection

    data class SaveImportedPreset(
        val name: String,
        val preampDb: Double,
        val sourceBands: List<EqBand>,
        override val candidateBands: List<EqBand>,
        override val maxSelectable: Int
    ) : PendingBandSelection

    data class SaveDacToPreset(
        val name: String,
        val preampDb: Double,
        val sourceBands: List<EqBand>,
        override val candidateBands: List<EqBand>,
        override val maxSelectable: Int
    ) : PendingBandSelection
}

data class ActiveDacSession(
    val deviceKey: String,
    val profile: DacProfile,
    val capabilities: DacCapabilities,
    val state: DacDeviceState,
    val isBusy: Boolean = false,
    val hasUnsavedFlashEdits: Boolean = false,
    val statusBanner: String? = null
)

data class MainUiState(
    val currentScreen: AppScreen = AppScreen.DEVICES,
    val detectedDevices: List<DetectedUsbDac> = emptyList(),
    val detectedBtDevices: List<DetectedBluetoothDac> = emptyList(),
    val hasBtPermission: Boolean = false,
    val isBtScanning: Boolean = false,
    val activeSession: ActiveDacSession? = null,
    val selectedBandIndex: Int = 0,
    val presets: List<EqPreset> = emptyList(),
    val prefs: AppPrefs = AppPrefs(),
    val catalogProfiles: List<DacProfile> = emptyList(),
    val usbLogs: List<String> = emptyList(),
    val pendingTargetDacSelection: PendingTargetDacSelection? = null,
    val pendingSlotSelection: PendingSlotSelection? = null,
    val pendingBandSelection: PendingBandSelection? = null,
    val toastMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DongleCtrlApp
    private val usbMonitor = app.usbMonitor
    private val btMonitor = app.btMonitor
    private val presetStore = app.presetStore
    private val prefsStore = app.prefsStore
    private val catalog = app.catalog

    private var activeTransport: DacTransport? = null
    private var activeDriver: DacProtocol? = null
    private var bandWriteDebounceJob: Job? = null
    private val debouncedWriteJobs = HashMap<String, Job>()
    private var incomingFramesJob: Job? = null
    private var telemetryPollJob: Job? = null
    private var lastNonBypassPresetId: Int? = null
    private val ioMutex = Mutex()
    @Volatile
    private var lastUserWriteAtMs: Long = 0L

    private val _uiState = MutableStateFlow(
        MainUiState(
            presets = presetStore.loadAll(),
            prefs = prefsStore.load(),
            catalogProfiles = catalog.profiles
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        usbMonitor.start()
        btMonitor.refreshBondedAndDiscovered()

        viewModelScope.launch {
            usbMonitor.detectedDevices.collect { devices ->
                _uiState.update { it.copy(detectedDevices = devices) }
                handleUsbDeviceListChanged(devices)
            }
        }

        viewModelScope.launch {
            btMonitor.detectedDevices.collect { btList ->
                _uiState.update { it.copy(detectedBtDevices = btList) }
            }
        }

        viewModelScope.launch {
            btMonitor.hasPermission.collect { granted ->
                _uiState.update { it.copy(hasBtPermission = granted) }
            }
        }

        viewModelScope.launch {
            btMonitor.isScanning.collect { scanning ->
                _uiState.update { it.copy(isBtScanning = scanning) }
            }
        }

        viewModelScope.launch {
            usbMonitor.logs.collect { logs ->
                _uiState.update { it.copy(usbLogs = logs) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeTransport?.close()
        btMonitor.stopLeScan()
        usbMonitor.stop()
    }

    fun navigateTo(screen: AppScreen) {
        val prevScreen = _uiState.value.currentScreen
        _uiState.update { it.copy(currentScreen = screen) }
        if (screen == AppScreen.DASHBOARD && prevScreen != AppScreen.DASHBOARD) {
            refreshActiveSessionFromHardware(includeBands = prevScreen != AppScreen.EQUALIZER)
        }
    }

    fun refreshActiveSessionFromHardware(includeBands: Boolean = true) {
        val session = _uiState.value.activeSession ?: return
        val tx = activeTransport ?: return
        val driver = activeDriver ?: return
        if (!tx.isConnected) return

        viewModelScope.launch {
            val refreshed = ioMutex.withLock {
                runCatching {
                    if (includeBands && !session.hasUnsavedFlashEdits) {
                        driver.readDeviceState(tx, session.profile)
                    } else {
                        driver.refreshLiveTelemetry(
                            transport = tx,
                            profile = session.profile,
                            currentState = session.state,
                            includeAllControls = true
                        )
                    }
                }.getOrNull()
            } ?: return@launch

            if (session.capabilities.bypassPresetId == null || refreshed.activePresetId != session.capabilities.bypassPresetId) {
                lastNonBypassPresetId = refreshed.activePresetId
            }

            _uiState.update { ui ->
                val cur = ui.activeSession ?: return@update ui
                if (cur.deviceKey != session.deviceKey) return@update ui
                val nextBands = if (includeBands && !cur.hasUnsavedFlashEdits && refreshed.bands.isNotEmpty()) {
                    refreshed.bands
                } else {
                    cur.state.bands
                }
                val merged = refreshed.copy(bands = nextBands)
                ui.copy(
                    activeSession = cur.copy(
                        state = merged,
                        statusBanner = buildSessionBanner(cur.profile, tx.transportType, merged)
                    )
                )
            }
        }
    }

    fun refreshUsbScan() {
        usbMonitor.refreshDevices(autoRequestPermission = true)
        btMonitor.refreshBondedAndDiscovered()
    }

    fun refreshBluetoothScan() {
        btMonitor.startLeScan()
    }

    fun onBluetoothPermissionResult() {
        btMonitor.refreshBondedAndDiscovered()
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun showToast(msg: String) {
        _uiState.update { it.copy(toastMessage = msg) }
    }

    private fun handleUsbDeviceListChanged(devices: List<DetectedUsbDac>) {
        val current = _uiState.value.activeSession
        if (current != null && !current.deviceKey.startsWith("bt_")) {
            val stillPresent = devices.any { it.device.deviceName == current.deviceKey }
            if (!stillPresent) {
                activeTransport?.close()
                activeTransport = null
                activeDriver = null
                _uiState.update {
                    it.copy(
                        activeSession = null,
                        currentScreen = if (it.currentScreen == AppScreen.SETTINGS) AppScreen.SETTINGS else AppScreen.DEVICES,
                        toastMessage = "${current.profile.model} disconnected"
                    )
                }
                return
            }
        }

        // Auto-connect if a permitted matched DAC is attached and no live session is open
        if (current == null) {
            val autoTarget = devices.firstOrNull { it.hasPermission && it.matchedProfile != null }
            if (autoTarget != null) {
                connectToUsbDevice(autoTarget, navigateToDashboard = true)
            }
        }
    }

    fun connectToBluetoothDevice(entry: DetectedBluetoothDac, navigateToDashboard: Boolean = true) {
        if (!btMonitor.hasRequiredPermissions()) {
            showToast("Grant Bluetooth permission to connect ${entry.displayName}")
            return
        }
        viewModelScope.launch {
            openBtConnection(entry, navigateToDashboard)
        }
    }

    private suspend fun openBtConnection(
        entry: DetectedBluetoothDac,
        navigateToDashboard: Boolean
    ): Boolean {
        if (!btMonitor.hasRequiredPermissions()) {
            showToast("Grant Bluetooth permission to connect ${entry.displayName}")
            return false
        }
        bandWriteDebounceJob?.cancel()
        activeTransport?.close()
        activeTransport = null

        val profile = entry.matchedProfile
        val driver = ProtocolRegistry.create(profile.protocol)
        val caps = driver.buildCapabilities(profile)

        val placeholderState = DacDeviceState(
            bands = DefaultBands.flatBands(caps.maxBands),
            maxVolume = caps.maxVolume
        )

        _uiState.update {
            it.copy(
                activeSession = ActiveDacSession(
                    deviceKey = entry.descriptor.deviceKey,
                    profile = profile,
                    capabilities = caps,
                    state = placeholderState,
                    isBusy = true,
                    statusBanner = "Connecting over Bluetooth..."
                ),
                selectedBandIndex = 0,
                currentScreen = if (navigateToDashboard) AppScreen.DASHBOARD else it.currentScreen
            )
        }

        val transport = btMonitor.openTransport(entry)
        if (transport == null) {
            _uiState.update { it.copy(activeSession = null, currentScreen = if (navigateToDashboard) AppScreen.DEVICES else it.currentScreen) }
            showToast("Unable to open Bluetooth link for ${entry.displayName}")
            return false
        }

        activeTransport = transport
        activeDriver = driver
        val runtimeProfile = profile.copy(transport = transport.transportType)

        val synced = runCatching {
            driver.readDeviceState(transport, runtimeProfile)
        }.getOrElse { err ->
            usbMonitor.log("BT initial sync fallback: ${err.message}")
            placeholderState.copy(diagnosticNote = "BT Connected (${err.message ?: "sync partial"})")
        }

        if (caps.bypassPresetId == null || synced.activePresetId != caps.bypassPresetId) {
            lastNonBypassPresetId = synced.activePresetId
        }

        val banner = buildSessionBanner(runtimeProfile, transport.transportType, synced)
        _uiState.update { state ->
            val cur = state.activeSession ?: return@update state
            if (cur.deviceKey != entry.descriptor.deviceKey) return@update state
            val resolvedCaps = driver.resolveCapabilities(runtimeProfile, synced)
            val finalBands = if (synced.bands.isEmpty()) DefaultBands.flatBands(resolvedCaps.maxBands) else synced.bands
            state.copy(
                activeSession = cur.copy(
                    profile = runtimeProfile,
                    capabilities = resolvedCaps.copy(maxBands = finalBands.size.coerceAtLeast(resolvedCaps.maxBands)),
                    state = synced.copy(bands = finalBands),
                    isBusy = false,
                    statusBanner = banner
                )
            )
        }
        startLiveSyncObservers(entry.descriptor.deviceKey, runtimeProfile, transport, driver)
        return true
    }

    fun connectToUsbDevice(entry: DetectedUsbDac, navigateToDashboard: Boolean = true) {
        if (!entry.hasPermission) {
            usbMonitor.requestPermission(entry.device)
            return
        }

        viewModelScope.launch {
            openUsbConnection(entry, navigateToDashboard)
        }
    }

    private suspend fun openUsbConnection(
        entry: DetectedUsbDac,
        navigateToDashboard: Boolean
    ): Boolean {
        if (!entry.hasPermission) {
            usbMonitor.requestPermission(entry.device)
            return false
        }
        bandWriteDebounceJob?.cancel()
        activeTransport?.close()
        activeTransport = null

        val profile = entry.effectiveProfile
        val driver = ProtocolRegistry.create(profile.protocol)
        val caps = driver.buildCapabilities(profile)

        val transport = usbMonitor.openTransport(entry)
        if (transport == null) {
            showToast("Unable to open USB interface for ${profile.model}")
            return false
        }

        activeTransport = transport
        activeDriver = driver

        val placeholderState = DacDeviceState(
            bands = DefaultBands.flatBands(caps.maxBands),
            maxVolume = caps.maxVolume
        )

        _uiState.update {
            it.copy(
                activeSession = ActiveDacSession(
                    deviceKey = entry.device.deviceName,
                    profile = profile,
                    capabilities = caps,
                    state = placeholderState,
                    isBusy = true,
                    statusBanner = "Syncing parameters..."
                ),
                selectedBandIndex = 0,
                currentScreen = if (navigateToDashboard) AppScreen.DASHBOARD else it.currentScreen
            )
        }

        val synced = runCatching {
            driver.readDeviceState(transport, profile)
        }.getOrElse { err ->
            usbMonitor.log("Initial sync fallback: ${err.message}")
            placeholderState.copy(diagnosticNote = "Connected (default view)")
        }

        if (caps.bypassPresetId == null || synced.activePresetId != caps.bypassPresetId) {
            lastNonBypassPresetId = synced.activePresetId
        }

        val banner = buildSessionBanner(profile, transport.transportType, synced)
        _uiState.update { state ->
            val cur = state.activeSession ?: return@update state
            if (cur.deviceKey != entry.device.deviceName) return@update state
            val resolvedCaps = driver.resolveCapabilities(profile, synced)
            val finalBands = if (synced.bands.isEmpty()) DefaultBands.flatBands(resolvedCaps.maxBands) else synced.bands
            state.copy(
                activeSession = cur.copy(
                    capabilities = resolvedCaps.copy(maxBands = finalBands.size.coerceAtLeast(resolvedCaps.maxBands)),
                    state = synced.copy(bands = finalBands),
                    isBusy = false,
                    statusBanner = banner
                )
            )
        }
        startLiveSyncObservers(entry.device.deviceName, profile, transport, driver)
        return true
    }

    fun disconnectSession() {
        stopLiveSyncObservers()
        bandWriteDebounceJob?.cancel()
        bandWriteDebounceJob = null
        activeTransport?.close()
        activeTransport = null
        activeDriver = null
        _uiState.update {
            it.copy(
                activeSession = null,
                currentScreen = AppScreen.DEVICES
            )
        }
    }

    private fun stopLiveSyncObservers() {
        incomingFramesJob?.cancel()
        incomingFramesJob = null
        telemetryPollJob?.cancel()
        telemetryPollJob = null
        debouncedWriteJobs.values.forEach { it.cancel() }
        debouncedWriteJobs.clear()
    }

    private fun buildSessionBanner(
        profile: DacProfile,
        transportType: TransportType,
        state: DacDeviceState
    ): String {
        return if (transportType == TransportType.BLUETOOTH_SPP || transportType == TransportType.BLUETOOTH_GATT) {
            buildList {
                state.bluetoothCodec?.let { add(it) }
                state.batteryPercent?.let { add("$it%") }
                add(if (transportType == TransportType.BLUETOOTH_GATT) "BLE" else "BT SPP")
            }.joinToString(" · ")
        } else {
            state.firmwareVersion?.let { "FW $it" } ?: profile.protocol.displayName
        }
    }

    private fun startLiveSyncObservers(
        deviceKey: String,
        profile: DacProfile,
        transport: DacTransport,
        driver: DacProtocol
    ) {
        stopLiveSyncObservers()
        incomingFramesJob = viewModelScope.launch {
            transport.incomingFrames.collect { frame ->
                _uiState.update { ui ->
                    val cur = ui.activeSession ?: return@update ui
                    if (cur.deviceKey != deviceKey) return@update ui
                    val nextState = driver.applyIncomingPacket(frame, profile, cur.state) ?: return@update ui
                    ui.copy(
                        activeSession = cur.copy(
                            state = nextState,
                            statusBanner = buildSessionBanner(profile, transport.transportType, nextState)
                        )
                    )
                }
            }
        }
        telemetryPollJob = viewModelScope.launch {
            var tick = 0
            var prevScreen = _uiState.value.currentScreen
            while (isActive && transport.isConnected) {
                delay(650)
                tick++
                if (!transport.isConnected) break
                if (System.currentTimeMillis() - lastUserWriteAtMs < 900L) continue
                val curUi = _uiState.value
                val curSession = curUi.activeSession ?: break
                if (curSession.deviceKey != deviceKey) break
                val returnedToDashboard = curUi.currentScreen == AppScreen.DASHBOARD && prevScreen != AppScreen.DASHBOARD
                prevScreen = curUi.currentScreen
                val pollFullControls = curUi.currentScreen == AppScreen.DASHBOARD && (returnedToDashboard || tick % 10 == 0)
                if (!ioMutex.tryLock()) continue
                val refreshed = try {
                    if (System.currentTimeMillis() - lastUserWriteAtMs >= 900L) {
                        runCatching {
                            driver.refreshLiveTelemetry(
                                transport = transport,
                                profile = profile,
                                currentState = curSession.state,
                                includeAllControls = pollFullControls
                            )
                        }.getOrNull()
                    } else null
                } finally {
                    ioMutex.unlock()
                }
                if (refreshed != null && System.currentTimeMillis() - lastUserWriteAtMs >= 900L) {
                    _uiState.update { ui ->
                        val cur = ui.activeSession ?: return@update ui
                        if (cur.deviceKey != deviceKey) return@update ui
                        // Merge live telemetry & hardware control state without clobbering offline bands currently being edited
                        val presetSwitched = refreshed.activePresetId != cur.state.activePresetId
                        val nextBands = if (!cur.hasUnsavedFlashEdits && presetSwitched && refreshed.bands.isNotEmpty()) {
                            refreshed.bands
                        } else {
                            cur.state.bands
                        }
                        val mergedState = cur.state.copy(
                            volume = refreshed.volume,
                            maxVolume = refreshed.maxVolume,
                            eqEnabled = refreshed.eqEnabled,
                            activePresetId = refreshed.activePresetId,
                            globalGainDb = if (!cur.hasUnsavedFlashEdits && presetSwitched) refreshed.globalGainDb else cur.state.globalGainDb,
                            bands = nextBands,
                            customPresetNames = if (refreshed.customPresetNames.isNotEmpty()) {
                                refreshed.customPresetNames
                            } else {
                                cur.state.customPresetNames
                            },
                            dacFilterIndex = refreshed.dacFilterIndex,
                            workModeIndex = refreshed.workModeIndex,
                            outputModeIndex = refreshed.outputModeIndex,
                            channelBalance = refreshed.channelBalance,
                            uacMode = refreshed.uacMode,
                            ledMode = refreshed.ledMode,
                            batteryPercent = refreshed.batteryPercent ?: cur.state.batteryPercent,
                            bluetoothCodec = refreshed.bluetoothCodec ?: cur.state.bluetoothCodec,
                            deviceName = refreshed.deviceName ?: cur.state.deviceName,
                            extraControlValues = refreshed.extraControlValues
                        )
                        if (mergedState == cur.state) return@update ui
                        ui.copy(
                            activeSession = cur.copy(
                                state = mergedState,
                                statusBanner = buildSessionBanner(profile, transport.transportType, mergedState)
                            )
                        )
                    }
                }
            }
        }
    }

    fun overrideProtocol(entry: DetectedUsbDac, family: ProtocolFamily) {
        prefsStore.setProtocolOverride(entry.device.vendorId, entry.device.productId, family)
        usbMonitor.refreshDevices(autoRequestPermission = false)
        showToast("Protocol set to ${family.displayName}")
    }

    fun selectBand(index: Int) {
        _uiState.update { it.copy(selectedBandIndex = index) }
    }

    fun updateBand(updated: EqBand) {
        val session = _uiState.value.activeSession ?: return
        val caps = session.capabilities
        val clamped = updated.copy(
            frequencyHz = updated.frequencyHz.coerceIn(10, 22000),
            gainDb = updated.gainDb.coerceIn(caps.minGainDb, caps.maxGainDb),
            q = updated.q.coerceIn(caps.minQ, caps.maxQ)
        )
        val newBands = session.state.bands.toMutableList()
        if (clamped.index in newBands.indices) {
            newBands[clamped.index] = clamped
        }
        _uiState.update {
            it.copy(
                activeSession = session.copy(
                    state = session.state.copy(bands = newBands),
                    hasUnsavedFlashEdits = true
                )
            )
        }

        if (_uiState.value.prefs.autoApplyEq) {
            scheduleLiveBandWrite(clamped)
        }
    }

    private fun scheduleLiveBandWrite(band: EqBand) {
        bandWriteDebounceJob?.cancel()
        bandWriteDebounceJob = viewModelScope.launch {
            delay(45)
            val session = _uiState.value.activeSession ?: return@launch
            val tx = activeTransport ?: return@launch
            val driver = activeDriver ?: return@launch
            val ok = ioMutex.withLock {
                lastUserWriteAtMs = System.currentTimeMillis()
                driver.writeEqBand(
                    transport = tx,
                    profile = session.profile,
                    band = band,
                    globalGainDb = session.state.globalGainDb,
                    presetId = session.state.activePresetId
                )
            }
            if (ok && session.capabilities.supportsRamRefresh) {
                ioMutex.withLock {
                    driver.refreshEqRam(tx, session.profile)
                }
            }
        }
    }

    fun applyAllEqNow() {
        val session = _uiState.value.activeSession ?: return
        val tx = activeTransport ?: return
        val driver = activeDriver ?: return

        viewModelScope.launch {
            setBusy(true)
            val ok = ioMutex.withLock {
                lastUserWriteAtMs = System.currentTimeMillis()
                driver.writeAllEqBands(
                    transport = tx,
                    profile = session.profile,
                    bands = session.state.bands,
                    globalGainDb = session.state.globalGainDb,
                    presetId = session.state.activePresetId
                )
            }
            setBusy(false)
            showToast(if (ok) "EQ applied to DAC RAM" else "Failed to apply EQ")
        }
    }

    fun saveToHardwareFlash() {
        val session = _uiState.value.activeSession ?: return
        val tx = activeTransport ?: return
        val driver = activeDriver ?: return

        bandWriteDebounceJob?.cancel()
        bandWriteDebounceJob = null

        viewModelScope.launch {
            setBusy(true)
            val saved = ioMutex.withLock {
                lastUserWriteAtMs = System.currentTimeMillis()
                val wroteAll = driver.writeAllEqBands(
                    tx,
                    session.profile,
                    session.state.bands,
                    session.state.globalGainDb,
                    session.state.activePresetId
                )
                wroteAll && driver.saveToFlash(tx, session.profile, session.state.activePresetId)
            }
            _uiState.update { state ->
                val cur = state.activeSession ?: return@update state
                state.copy(
                    activeSession = cur.copy(
                        isBusy = false,
                        hasUnsavedFlashEdits = if (saved) false else cur.hasUnsavedFlashEdits
                    ),
                    toastMessage = if (saved) "Saved permanently to DAC Flash" else "Flash save failed"
                )
            }
        }
    }

    fun resetEqFlat() {
        val session = _uiState.value.activeSession ?: return
        val flat = DefaultBands.flatBands(session.state.bands.size.coerceAtLeast(session.capabilities.maxBands))
        _uiState.update {
            it.copy(
                activeSession = session.copy(
                    state = session.state.copy(bands = flat, globalGainDb = 0.0),
                    hasUnsavedFlashEdits = true
                )
            )
        }
        val tx = activeTransport ?: return
        val driver = activeDriver ?: return
        viewModelScope.launch {
            setBusy(true)
            driver.resetEq(tx, session.profile)
            driver.writeAllEqBands(tx, session.profile, flat, 0.0, session.state.activePresetId)
            setBusy(false)
            showToast("EQ reset to flat")
        }
    }

    private fun availableTargetDacs(): List<TargetDacOption> {
        val state = _uiState.value
        val activeKey = state.activeSession?.deviceKey
        val list = mutableListOf<TargetDacOption>()

        state.detectedDevices
            .filter { it.isSupportedCandidate || it.device.deviceName == activeKey }
            .forEach { usb ->
                list.add(
                    TargetDacOption.UsbTarget(
                        entry = usb,
                        isActiveSession = usb.device.deviceName == activeKey
                    )
                )
            }

        state.detectedBtDevices.forEach { bt ->
            list.add(
                TargetDacOption.BtTarget(
                    entry = bt,
                    isActiveSession = bt.descriptor.deviceKey == activeKey
                )
            )
        }

        val session = state.activeSession
        if (session != null && list.none { it.deviceKey == session.deviceKey }) {
            list.add(
                TargetDacOption.ActiveSessionOnly(
                    deviceKey = session.deviceKey,
                    title = session.profile.model,
                    subtitle = "${session.profile.protocol.displayName} · Connected"
                )
            )
        }
        return list
    }

    fun applyPreset(preset: EqPreset, forceActiveSessionOnly: Boolean = false) {
        val active = _uiState.value.activeSession
        if (forceActiveSessionOnly) {
            if (active == null) {
                showToast("Connect a DAC to apply \"${preset.name}\"")
                return
            }
            continueApplyToActiveSession(preset)
            return
        }

        val targets = availableTargetDacs()
        when {
            targets.isEmpty() -> {
                showToast("No DAC connected or detected")
            }
            targets.size > 1 -> {
                _uiState.update {
                    it.copy(
                        pendingTargetDacSelection = PendingTargetDacSelection(
                            preset = preset,
                            options = targets
                        )
                    )
                }
            }
            else -> {
                confirmTargetDacOption(targets.first(), preset)
            }
        }
    }

    fun confirmTargetDacSelection(option: TargetDacOption) {
        val pending = _uiState.value.pendingTargetDacSelection ?: return
        _uiState.update { it.copy(pendingTargetDacSelection = null) }
        confirmTargetDacOption(option, pending.preset)
    }

    fun dismissTargetDacSelection() {
        _uiState.update { it.copy(pendingTargetDacSelection = null) }
    }

    private fun confirmTargetDacOption(option: TargetDacOption, preset: EqPreset) {
        val currentKey = _uiState.value.activeSession?.deviceKey
        if (currentKey != null && option.deviceKey == currentKey) {
            continueApplyToActiveSession(preset)
            return
        }

        viewModelScope.launch {
            val connected = when (option) {
                is TargetDacOption.UsbTarget -> openUsbConnection(option.entry, navigateToDashboard = false)
                is TargetDacOption.BtTarget -> openBtConnection(option.entry, navigateToDashboard = false)
                is TargetDacOption.ActiveSessionOnly -> _uiState.value.activeSession != null
            }
            if (connected) {
                continueApplyToActiveSession(preset)
            }
        }
    }

    private fun continueApplyToActiveSession(preset: EqPreset) {
        val session = _uiState.value.activeSession ?: return
        val presetsForUac = session.capabilities.activePresetsForUac(session.state.uacMode)
        val writableSlots = presetsForUac.filter { it.id in session.capabilities.writablePresetIds }
        when {
            writableSlots.size > 1 -> {
                _uiState.update {
                    it.copy(
                        pendingSlotSelection = PendingSlotSelection(
                            preset = preset,
                            dacName = session.profile.model,
                            slots = writableSlots,
                            customNames = session.state.customPresetNames,
                            currentPresetId = session.state.activePresetId
                        )
                    )
                }
            }
            writableSlots.size == 1 -> {
                proceedApplyPresetWithSlot(preset, targetSlotId = writableSlots.first().id)
            }
            else -> {
                proceedApplyPresetWithSlot(preset, targetSlotId = null)
            }
        }
    }

    fun confirmSlotSelection(slotId: Int) {
        val pending = _uiState.value.pendingSlotSelection ?: return
        _uiState.update { it.copy(pendingSlotSelection = null) }
        proceedApplyPresetWithSlot(pending.preset, targetSlotId = slotId)
    }

    fun dismissSlotSelection() {
        _uiState.update { it.copy(pendingSlotSelection = null) }
    }

    private fun proceedApplyPresetWithSlot(preset: EqPreset, targetSlotId: Int?) {
        val session = _uiState.value.activeSession ?: return
        val targetCount = session.state.bands.size.coerceAtLeast(session.capabilities.maxBands)
        when (val resolution = PresetBandHelper.resolveBandsForTarget(preset.bands, targetCount)) {
            is PresetBandHelper.BandResolution.Ready -> {
                commitAppliedBands(preset.name, resolution.bands, preset.preampDb, targetSlotId)
            }
            is PresetBandHelper.BandResolution.NeedsSelection -> {
                _uiState.update {
                    it.copy(
                        pendingBandSelection = PendingBandSelection.ApplyPresetToDac(
                            preset = preset,
                            targetSlotId = targetSlotId,
                            targetDacBands = targetCount,
                            dacName = session.profile.model,
                            candidateBands = resolution.candidateBands,
                            maxSelectable = resolution.maxSelectable
                        )
                    )
                }
            }
        }
    }

    private fun commitAppliedBands(
        presetName: String,
        resolvedBands: List<EqBand>,
        preampDb: Double,
        targetSlotId: Int?
    ) {
        val session = _uiState.value.activeSession ?: return
        val tx = activeTransport ?: return
        val driver = activeDriver ?: return

        val adapted = resolvedBands.mapIndexed { idx, src ->
            src.copy(
                index = idx,
                gainDb = src.gainDb.coerceIn(session.capabilities.minGainDb, session.capabilities.maxGainDb),
                q = src.q.coerceIn(session.capabilities.minQ, session.capabilities.maxQ),
                filterType = if (src.filterType in session.capabilities.supportedFilters) src.filterType else FilterType.PEAK
            )
        }
        val clampedPreamp = preampDb.coerceIn(session.capabilities.globalGainRange)
        val slotIdToUse = targetSlotId ?: session.state.activePresetId
        val slotLabel = session.capabilities.activePresetsForUac(session.state.uacMode)
            .firstOrNull { it.id == slotIdToUse }
            ?.let { session.state.customPresetNames[it.id]?.takeIf { n -> n.isNotBlank() } ?: it.label }

        bandWriteDebounceJob?.cancel()
        bandWriteDebounceJob = null

        val nextEqEnabled = if (
            targetSlotId != null &&
            session.capabilities.bypassPresetId != targetSlotId
        ) {
            true
        } else {
            session.state.eqEnabled
        }

        _uiState.update {
            it.copy(
                pendingBandSelection = null,
                selectedBandIndex = 0,
                activeSession = session.copy(
                    state = session.state.copy(
                        activePresetId = slotIdToUse,
                        eqEnabled = nextEqEnabled,
                        bands = adapted,
                        globalGainDb = clampedPreamp
                    ),
                    isBusy = true,
                    hasUnsavedFlashEdits = true
                )
            )
        }

        viewModelScope.launch {
            val savedCleanly = ioMutex.withLock {
                lastUserWriteAtMs = System.currentTimeMillis()
                // 1. Set target custom slot on DAC first so writes never touch factory/wrong slots
                if (targetSlotId != null) {
                    driver.selectPreset(tx, session.profile, targetSlotId)
                    delay(80)
                    if (!session.state.eqEnabled && session.capabilities.bypassPresetId != targetSlotId) {
                        lastNonBypassPresetId = targetSlotId
                        if (!session.capabilities.eqToggleUsesBypass) {
                            driver.setEqEnabled(tx, session.profile, true)
                            delay(40)
                        }
                    }
                }
                // 2. Write bands + global preamp to DAC
                val wroteAll = driver.writeAllEqBands(
                    transport = tx,
                    profile = session.profile,
                    bands = adapted,
                    globalGainDb = clampedPreamp,
                    presetId = slotIdToUse
                )
                if (!wroteAll) return@withLock false
                // 3. Persist to flash on DAC
                val savedFlash = if (session.capabilities.supportsFlashSave) {
                    driver.saveToFlash(tx, session.profile, slotIdToUse)
                } else {
                    true
                }
                // 4. Refresh live DSP RAM
                if (session.capabilities.supportsRamRefresh) {
                    driver.refreshEqRam(tx, session.profile)
                }
                savedFlash
            }

            _uiState.update { state ->
                val cur = state.activeSession ?: return@update state
                val toast = if (!savedCleanly) {
                    "Failed to apply \"$presetName\" to DAC"
                } else if (slotLabel != null && session.capabilities.supportsFlashSave) {
                    "Applied & saved \"$presetName\" to $slotLabel"
                } else if (slotLabel != null) {
                    "Applied \"$presetName\" to $slotLabel"
                } else if (session.capabilities.supportsFlashSave) {
                    "Applied & saved \"$presetName\""
                } else {
                    "Applied \"$presetName\""
                }
                state.copy(
                    activeSession = cur.copy(
                        isBusy = false,
                        hasUnsavedFlashEdits = if (savedCleanly) false else cur.hasUnsavedFlashEdits
                    ),
                    toastMessage = toast
                )
            }
        }
    }

    fun saveOrUpdateUserPreset(
        existingId: String?,
        name: String,
        preampDb: Double,
        bands: List<EqBand>
    ) {
        val cleanName = name.trim().ifEmpty { "Custom EQ" }
        val normalized = PresetBandHelper.padToCount(
            bands.take(PresetBandHelper.STANDARD_PRESET_BANDS),
            PresetBandHelper.STANDARD_PRESET_BANDS
        )
        val presetId = existingId?.takeIf { it.isNotBlank() } ?: "user_${System.currentTimeMillis()}"
        val saved = EqPreset(
            id = presetId,
            name = cleanName,
            preampDb = preampDb.coerceIn(-12.0, 12.0),
            bands = normalized,
            builtIn = false
        )
        val updatedList = presetStore.saveUserPreset(saved)
        _uiState.update {
            it.copy(
                presets = updatedList,
                pendingBandSelection = null,
                toastMessage = "Saved preset \"$cleanName\""
            )
        }
    }

    fun saveCurrentAsUserPreset(name: String) {
        val session = _uiState.value.activeSession ?: return
        val cleanName = name.trim().ifEmpty { "Custom EQ" }
        when (val res = PresetBandHelper.resolveStandardTenBands(session.state.bands)) {
            is PresetBandHelper.BandResolution.Ready -> {
                saveOrUpdateUserPreset(
                    existingId = null,
                    name = cleanName,
                    preampDb = session.state.globalGainDb,
                    bands = res.bands
                )
            }
            is PresetBandHelper.BandResolution.NeedsSelection -> {
                _uiState.update {
                    it.copy(
                        pendingBandSelection = PendingBandSelection.SaveDacToPreset(
                            name = cleanName,
                            preampDb = session.state.globalGainDb,
                            sourceBands = session.state.bands,
                            candidateBands = res.candidateBands,
                            maxSelectable = res.maxSelectable
                        )
                    )
                }
            }
        }
    }

    fun deleteUserPreset(id: String) {
        val updatedList = presetStore.deleteUserPreset(id)
        _uiState.update { it.copy(presets = updatedList, toastMessage = "Preset deleted") }
    }

    fun importAutoEqToPresets(suggestedName: String, text: String) {
        val parsed = PeqTextParser.parse(text, maxBands = 64)
        if (parsed.bands.isEmpty()) {
            showToast("No valid PEQ filters found in file")
            return
        }
        val cleanName = suggestedName.trim().ifEmpty { "Imported EQ" }
        when (val res = PresetBandHelper.resolveStandardTenBands(parsed.bands)) {
            is PresetBandHelper.BandResolution.Ready -> {
                saveOrUpdateUserPreset(
                    existingId = null,
                    name = cleanName,
                    preampDb = parsed.preampDb,
                    bands = res.bands
                )
                val activeCount = PresetBandHelper.activeBands(res.bands).size
                showToast("Imported \"$cleanName\" ($activeCount active / 10 bands)")
            }
            is PresetBandHelper.BandResolution.NeedsSelection -> {
                _uiState.update {
                    it.copy(
                        pendingBandSelection = PendingBandSelection.SaveImportedPreset(
                            name = cleanName,
                            preampDb = parsed.preampDb,
                            sourceBands = parsed.bands,
                            candidateBands = res.candidateBands,
                            maxSelectable = res.maxSelectable
                        )
                    )
                }
            }
        }
    }

    fun confirmPendingBandSelection(selectedCandidateIndices: List<Int>, customPresetName: String? = null) {
        val pending = _uiState.value.pendingBandSelection ?: return
        when (pending) {
            is PendingBandSelection.ApplyPresetToDac -> {
                val res = PresetBandHelper.resolveBandsForTarget(
                    presetBands = pending.preset.bands,
                    targetMaxBands = pending.targetDacBands,
                    selectedCandidateIndices = selectedCandidateIndices
                )
                if (res is PresetBandHelper.BandResolution.Ready) {
                    commitAppliedBands(pending.preset.name, res.bands, pending.preset.preampDb, pending.targetSlotId)
                }
            }
            is PendingBandSelection.SaveImportedPreset -> {
                val res = PresetBandHelper.resolveStandardTenBands(
                    sourceBands = pending.sourceBands,
                    selectedCandidateIndices = selectedCandidateIndices
                )
                if (res is PresetBandHelper.BandResolution.Ready) {
                    val finalName = customPresetName?.trim()?.ifEmpty { pending.name } ?: pending.name
                    saveOrUpdateUserPreset(
                        existingId = null,
                        name = finalName,
                        preampDb = pending.preampDb,
                        bands = res.bands
                    )
                }
            }
            is PendingBandSelection.SaveDacToPreset -> {
                val res = PresetBandHelper.resolveStandardTenBands(
                    sourceBands = pending.sourceBands,
                    selectedCandidateIndices = selectedCandidateIndices
                )
                if (res is PresetBandHelper.BandResolution.Ready) {
                    val finalName = customPresetName?.trim()?.ifEmpty { pending.name } ?: pending.name
                    saveOrUpdateUserPreset(
                        existingId = null,
                        name = finalName,
                        preampDb = pending.preampDb,
                        bands = res.bands
                    )
                }
            }
        }
    }

    fun dismissPendingBandSelection() {
        _uiState.update { it.copy(pendingBandSelection = null) }
    }

    fun exportPresetAutoEqText(preset: EqPreset): String =
        PeqTextParser.exportAutoEq(preset.bands, preset.preampDb)

    // --- Hardware Controls ---

    fun setGlobalGain(gainDb: Double) {
        updateHardwareState(
            transform = { it.copy(globalGainDb = gainDb) },
            markFlashDirty = true,
            debounceKey = "globalGain"
        ) { tx, profile, driver ->
            driver.setGlobalGain(tx, profile, gainDb)
        }
    }

    fun setEqEnabled(enabled: Boolean) {
        val session = _uiState.value.activeSession ?: return
        val bypassId = session.capabilities.bypassPresetId
        val nextPresetId = if (session.capabilities.eqToggleUsesBypass && bypassId != null) {
            when {
                !enabled -> bypassId
                session.state.activePresetId == bypassId ->
                    lastNonBypassPresetId?.takeIf { it != bypassId }
                        ?: session.capabilities.writablePresetIds.firstOrNull()
                        ?: session.capabilities.activePresetsForUac(session.state.uacMode)
                            .firstOrNull { it.id != bypassId }?.id
                        ?: session.state.activePresetId
                else -> session.state.activePresetId
            }
        } else {
            session.state.activePresetId
        }
        updateHardwareState(
            transform = { it.copy(eqEnabled = enabled, activePresetId = nextPresetId) }
        ) { tx, profile, driver ->
            driver.setEqEnabled(tx, profile, enabled)
        }
    }

    fun selectHardwarePreset(presetId: Int) {
        val session = _uiState.value.activeSession ?: return
        val bypassId = session.capabilities.bypassPresetId
        val isBypass = bypassId != null && presetId == bypassId
        if (!isBypass) lastNonBypassPresetId = presetId
        val nextEqEnabled = if (session.capabilities.eqToggleUsesBypass) !isBypass else session.state.eqEnabled

        _uiState.update {
            it.copy(
                activeSession = session.copy(
                    state = session.state.copy(
                        activePresetId = presetId,
                        eqEnabled = nextEqEnabled
                    )
                )
            )
        }
        val tx = activeTransport ?: return
        val driver = activeDriver ?: return
        viewModelScope.launch {
            setBusy(true)
            val refreshed = ioMutex.withLock {
                lastUserWriteAtMs = System.currentTimeMillis()
                driver.selectPreset(tx, session.profile, presetId)
                delay(35)
                val baseState = _uiState.value.activeSession?.state ?: session.state
                runCatching { driver.refreshPresetEqState(tx, session.profile, baseState, presetId) }.getOrNull()
            }
            _uiState.update { st ->
                val cur = st.activeSession ?: return@update st
                st.copy(
                    activeSession = cur.copy(
                        state = refreshed?.copy(
                            activePresetId = presetId,
                            eqEnabled = if (cur.capabilities.eqToggleUsesBypass) !isBypass else refreshed.eqEnabled
                        ) ?: cur.state,
                        isBusy = false
                    )
                )
            }
        }
    }

    fun setDacFilter(index: Int) {
        val flashDirty = _uiState.value.activeSession?.capabilities?.supportsFlashSave == true
        updateHardwareState(transform = { it.copy(dacFilterIndex = index) }, markFlashDirty = flashDirty) { tx, profile, driver ->
            driver.setDacFilter(tx, profile, index)
        }
    }

    fun setOutputMode(index: Int) {
        val flashDirty = _uiState.value.activeSession?.capabilities?.supportsFlashSave == true
        updateHardwareState(transform = { it.copy(outputModeIndex = index) }, markFlashDirty = flashDirty) { tx, profile, driver ->
            driver.setOutputMode(tx, profile, index)
        }
    }

    fun setWorkMode(index: Int) {
        val flashDirty = _uiState.value.activeSession?.capabilities?.supportsFlashSave == true
        updateHardwareState(transform = { it.copy(workModeIndex = index) }, markFlashDirty = flashDirty) { tx, profile, driver ->
            driver.setWorkMode(tx, profile, index)
        }
    }

    fun setVolume(vol: Int) {
        updateHardwareState(
            transform = { it.copy(volume = vol) },
            debounceKey = "volume"
        ) { tx, profile, driver ->
            driver.setVolume(tx, profile, vol)
        }
    }

    fun setChannelBalance(balance: Int) {
        val flashDirty = _uiState.value.activeSession?.capabilities?.supportsFlashSave == true
        updateHardwareState(
            transform = { it.copy(channelBalance = balance) },
            markFlashDirty = flashDirty,
            debounceKey = "balance"
        ) { tx, profile, driver ->
            driver.setChannelBalance(tx, profile, balance)
        }
    }

    fun setMicGain(gainDb: Int) {
        val flashDirty = _uiState.value.activeSession?.capabilities?.supportsFlashSave == true
        updateHardwareState(
            transform = { it.copy(micGainDb = gainDb) },
            markFlashDirty = flashDirty,
            debounceKey = "micGain"
        ) { tx, profile, driver ->
            driver.setMicGain(tx, profile, gainDb)
        }
    }

    fun setMicMonitor(enabled: Boolean) {
        val flashDirty = _uiState.value.activeSession?.capabilities?.supportsFlashSave == true
        updateHardwareState(transform = { it.copy(micMonitorEnabled = enabled) }, markFlashDirty = flashDirty) { tx, profile, driver ->
            driver.setMicMonitor(tx, profile, enabled)
        }
    }

    fun setMicEnc(enabled: Boolean) {
        val flashDirty = _uiState.value.activeSession?.capabilities?.supportsFlashSave == true
        updateHardwareState(transform = { it.copy(micEncEnabled = enabled) }, markFlashDirty = flashDirty) { tx, profile, driver ->
            driver.setMicEnc(tx, profile, enabled)
        }
    }

    fun setMicSwitch(enabled: Boolean) {
        updateHardwareState(transform = { it.copy(micSwitchEnabled = enabled) }) { tx, profile, driver ->
            driver.setMicSwitch(tx, profile, enabled)
        }
    }

    fun setUacMode(mode: Int) {
        updateHardwareState(transform = { it.copy(uacMode = mode) }) { tx, profile, driver ->
            driver.setUacMode(tx, profile, mode)
        }
    }

    fun setLedMode(mode: Int) {
        val flashDirty = _uiState.value.activeSession?.capabilities?.supportsFlashSave == true
        updateHardwareState(transform = { it.copy(ledMode = mode) }, markFlashDirty = flashDirty) { tx, profile, driver ->
            driver.setLedMode(tx, profile, mode)
        }
    }

    fun setSurround3d(enabled: Boolean) {
        val flashDirty = _uiState.value.activeSession?.capabilities?.supportsFlashSave == true
        updateHardwareState(transform = { it.copy(surround3dEnabled = enabled) }, markFlashDirty = flashDirty) { tx, profile, driver ->
            driver.setSurround3d(tx, profile, enabled)
        }
    }

    fun setExtraControl(key: String, value: DacControlValue) {
        val session = _uiState.value.activeSession ?: return
        val flashDirty = session.capabilities.supportsFlashSave
        val ctrlKind = session.capabilities.extraControls.firstOrNull { it.key == key }?.kind
        val debounce = if (ctrlKind == com.suprcharg3d.dongleCTRL.protocol.ControlKind.SLIDER ||
            ctrlKind == com.suprcharg3d.dongleCTRL.protocol.ControlKind.DUAL_SLIDER_SWITCH
        ) "extra_$key" else null
        updateHardwareState(
            transform = { cur ->
                val updatedMap = cur.extraControlValues.toMutableMap()
                updatedMap[key] = value
                var nextMaxVol = cur.maxVolume
                var nextVol = cur.volume
                val extrasMeta = session.capabilities.extraControls
                if (key == "vol_control") {
                    val optLabel = extrasMeta.firstOrNull { it.key == "vol_control" }
                        ?.options?.firstOrNull { it.id == value.intValue }?.label
                    val newStepMax = when {
                        optLabel?.contains("120") == true -> 120
                        optLabel?.contains("60") == true -> 60
                        value.intValue == 1 -> 120
                        else -> 60
                    }
                    extrasMeta.filter { it.allowMaxValueChange }.forEach { meta ->
                        updatedMap[meta.key]?.let { existing ->
                            updatedMap[meta.key] = existing.copy(
                                dynamicMax = newStepMax,
                                intValue = existing.intValue.coerceIn(meta.min, newStepMax)
                            )
                        }
                    }
                    val lim = updatedMap["vol_limit"]?.intValue?.takeIf { it in 20..newStepMax }
                    nextMaxVol = lim ?: newStepMax
                    nextVol = nextVol.coerceIn(0, nextMaxVol)
                    extrasMeta.filter { it.allowLimit }.forEach { meta ->
                        updatedMap[meta.key]?.let { existing ->
                            updatedMap[meta.key] = existing.copy(
                                dynamicMax = nextMaxVol,
                                intValue = existing.intValue.coerceIn(meta.min, nextMaxVol)
                            )
                        }
                    }
                } else if (key == "vol_limit") {
                    val stepOpt = updatedMap["vol_control"]?.intValue
                    val optLabel = extrasMeta.firstOrNull { it.key == "vol_control" }
                        ?.options?.firstOrNull { it.id == stepOpt }?.label
                    val stepMax = when {
                        optLabel?.contains("120") == true -> 120
                        optLabel?.contains("60") == true -> 60
                        stepOpt == 1 -> 120
                        else -> session.capabilities.maxVolume.coerceAtLeast(60)
                    }
                    nextMaxVol = value.intValue.coerceIn(20, stepMax)
                    nextVol = nextVol.coerceIn(0, nextMaxVol)
                    extrasMeta.filter { it.allowLimit }.forEach { meta ->
                        updatedMap[meta.key]?.let { existing ->
                            updatedMap[meta.key] = existing.copy(
                                dynamicMax = nextMaxVol,
                                intValue = existing.intValue.coerceIn(meta.min, nextMaxVol)
                            )
                        }
                    }
                }
                cur.copy(
                    volume = nextVol,
                    maxVolume = nextMaxVol,
                    extraControlValues = updatedMap
                )
            },
            markFlashDirty = flashDirty,
            debounceKey = debounce
        ) { tx, profile, driver ->
            val ok = driver.setExtraControl(tx, profile, key, value)
            if (ok && (key == "vol_control" || key == "cb_probe_diag" || ctrlKind == com.suprcharg3d.dongleCTRL.protocol.ControlKind.ACTION)) {
                delay(220)
                val baseState = _uiState.value.activeSession?.state
                val refreshed = if (key.endsWith("_reset") || key.endsWith("_reset_all")) {
                    runCatching { driver.readDeviceState(tx, profile) }.getOrNull()
                } else if (baseState != null) {
                    runCatching { driver.refreshLiveTelemetry(tx, profile, baseState, includeAllControls = true) }.getOrNull()
                } else null
                if (refreshed != null) {
                    if (key == "cb_probe_diag" && refreshed.diagnosticNote != null) {
                        usbMonitor.log(refreshed.diagnosticNote!!)
                    }
                    _uiState.update { ui ->
                        val cur = ui.activeSession ?: return@update ui
                        if (cur.deviceKey != session.deviceKey) return@update ui
                        val nextCaps = driver.resolveCapabilities(cur.profile, refreshed)
                        ui.copy(activeSession = cur.copy(capabilities = nextCaps, state = refreshed))
                    }
                }
            }
            ok
        }
    }

    fun setDeviceName(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        updateHardwareState(
            transform = { it.copy(deviceName = clean) }
        ) { tx, profile, driver ->
            val ok = driver.setDeviceName(tx, profile, clean)
            showToast(if (ok) "Device name updated" else "Failed to rename device")
            ok
        }
    }

    fun setPresetName(presetId: Int, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val session = _uiState.value.activeSession ?: return
        val maxChars = session.capabilities.presetNameMaxChars.coerceIn(4, 32)
        val clamped = if (clean.length > maxChars) clean.take(maxChars) else clean
        updateHardwareState(
            transform = { cur ->
                cur.copy(customPresetNames = cur.customPresetNames + (presetId to clamped))
            }
        ) { tx, profile, driver ->
            val ok = driver.setPresetName(tx, profile, presetId, clamped)
            showToast(if (ok) "Preset renamed to \"$clamped\"" else "Failed to rename preset")
            ok
        }
    }

    fun sendDeviceAction(cmdId: Int, label: String, disconnectAfter: Boolean = false) {
        val session = _uiState.value.activeSession ?: return
        val tx = activeTransport ?: return
        val driver = activeDriver ?: return
        viewModelScope.launch {
            lastUserWriteAtMs = System.currentTimeMillis()
            val ok = ioMutex.withLock {
                driver.sendDeviceAction(tx, session.profile, cmdId)
            }
            showToast(if (ok) "$label command sent" else "$label failed")
            if (ok && disconnectAfter) {
                delay(300)
                disconnectSession()
            } else if (ok) {
                delay(350)
                val refreshed = ioMutex.withLock {
                    runCatching { driver.readDeviceState(tx, session.profile) }.getOrNull()
                }
                if (refreshed != null) {
                    _uiState.update { st ->
                        val cur = st.activeSession ?: return@update st
                        st.copy(activeSession = cur.copy(state = refreshed))
                    }
                }
            }
        }
    }

    private fun updateHardwareState(
        transform: (DacDeviceState) -> DacDeviceState,
        markFlashDirty: Boolean = false,
        debounceKey: String? = null,
        action: suspend (DacTransport, DacProfile, DacProtocol) -> Boolean
    ) {
        val session = _uiState.value.activeSession ?: return
        _uiState.update {
            it.copy(
                activeSession = session.copy(
                    state = transform(session.state),
                    hasUnsavedFlashEdits = session.hasUnsavedFlashEdits || markFlashDirty
                )
            )
        }
        val tx = activeTransport ?: return
        val driver = activeDriver ?: return
        lastUserWriteAtMs = System.currentTimeMillis()
        if (debounceKey != null) {
            debouncedWriteJobs[debounceKey]?.cancel()
            debouncedWriteJobs[debounceKey] = viewModelScope.launch {
                delay(55)
                ioMutex.withLock {
                    lastUserWriteAtMs = System.currentTimeMillis()
                    action(tx, session.profile, driver)
                }
            }
        } else {
            viewModelScope.launch {
                ioMutex.withLock {
                    lastUserWriteAtMs = System.currentTimeMillis()
                    action(tx, session.profile, driver)
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        _uiState.update { state ->
            state.copy(activeSession = state.activeSession?.copy(isBusy = busy))
        }
    }

    // --- App Preferences ---

    fun setAutoApplyEq(enabled: Boolean) {
        prefsStore.setAutoApplyEq(enabled)
        _uiState.update { it.copy(prefs = it.prefs.copy(autoApplyEq = enabled)) }
    }

    fun setAutoOpenOnUsb(enabled: Boolean) {
        prefsStore.setAutoOpenOnUsb(enabled)
        app.applyUsbAutoOpenSetting(enabled)
        _uiState.update { it.copy(prefs = it.prefs.copy(autoOpenOnUsb = enabled)) }
    }

    fun setMuteOnWrite(enabled: Boolean) {
        prefsStore.setMuteOnWrite(enabled)
        _uiState.update { it.copy(prefs = it.prefs.copy(muteOnWrite = enabled)) }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefsStore.setThemeMode(mode)
        _uiState.update { it.copy(prefs = it.prefs.copy(themeMode = mode)) }
    }

    fun clearUsbLogs() {
        usbMonitor.clearLogs()
    }
}
