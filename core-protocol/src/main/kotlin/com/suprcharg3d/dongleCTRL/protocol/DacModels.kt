package com.suprcharg3d.dongleCTRL.protocol

import com.suprcharg3d.dongleCTRL.dsp.DefaultBands
import com.suprcharg3d.dongleCTRL.dsp.EqBand
import com.suprcharg3d.dongleCTRL.dsp.FilterType

enum class ProtocolFamily(val displayName: String) {
    CB("CB / TTGK DSP (0x4B)"),
    CB1300D("CB1300D Surround DSP (0x4B)"),
    CB1100AU("CB1100AU Extended DSP (0xCB)"),
    KT02H20("KT02H20 5-Band DSP"),
    KT0231H("KT0231H 6-Band DSP"),
    KT1213("KT1213 / MT2000 15-Band (0x57)"),
    CX31993("CX31993 Conexant 9-Band"),
    GM55("GM55 10-Band DSP (0x02)"),
    FIIO_HID("FiiO HID Control (0xAA/0xBB)"),
    FIIO_V2("FiiO PEQ V2 HID"),
    FIIO_GIGAWIT("FiiO GigaWit Bridge"),
    FIIO_RESTMOMENT("FiiO Desktop Serial (0xFC/0xFA)"),
    FIIO_BT_GAIA("FiiO Bluetooth GAIA"),
    FIIO_BT_UNIVERSAL("FiiO Bluetooth Universal"),
    FIIO_BT_BR13("FiiO Bluetooth RCSP"),
    COMTRUE_EP0("ComTrue EP0 (0xC1/0xA5)"),
    BES_MINO("BES Mino BT (11 00 00)"),
    BLUETRUM_V1("Bluetrum v1 BT (FF 04)"),
    BLUETRUM_V2("Bluetrum v2 BT (FF A1)"),
    TTGK_INFO("TTGK Chip Info (0x0E)"),
    NICEHCK_ORIGIN_BT("NICEHCK OriG in BT"),
    MOONDROP_SPV("Moondrop SPV (0x4B)"),
    MOONDROP_COMTURE("Moondrop ComTrue EP0 (0xC0/0xA5)"),
    MOONDROP_FREEMAN3("Moondrop Freeman3 (CAF)"),
    MOONDROP_BLE_SOURCE("Moondrop BLE Source (0xA5)"),
    MOONDROP_AIROHA("Moondrop Airoha Race"),
    MOONDROP_JIELI_RCSP("Moondrop Jieli RCSP"),
    MOONDROP_WMIC("Moondrop Wireless Mic"),
    QUALCOMM_GAIA_V3("Qualcomm GAIA v3");

    val usesEp0Control: Boolean
        get() = this in setOf(
            FIIO_HID,
            FIIO_V2,
            FIIO_GIGAWIT,
            COMTRUE_EP0,
            MOONDROP_SPV,
            MOONDROP_COMTURE
        )

    companion object {
        fun fromWireName(name: String): ProtocolFamily = when (name.uppercase()) {
            "CB" -> CB
            "CB1300D" -> CB1300D
            "CB1100AU" -> CB1100AU
            "KT02H20" -> KT02H20
            "KT0231H" -> KT0231H
            "KT1213" -> KT1213
            "CX31993" -> CX31993
            "GM55" -> GM55
            "FIIO_HID" -> FIIO_HID
            "FIIO_V2" -> FIIO_V2
            "FIIO_GIGAWIT" -> FIIO_GIGAWIT
            "FIIO_RESTMOMENT" -> FIIO_RESTMOMENT
            "FIIO_BT_GAIA" -> FIIO_BT_GAIA
            "FIIO_BT_UNIVERSAL" -> FIIO_BT_UNIVERSAL
            "FIIO_BT_BR13" -> FIIO_BT_BR13
            "COMTRUE_EP0" -> COMTRUE_EP0
            "BES_MINO" -> BES_MINO
            "BLUETRUM_V1" -> BLUETRUM_V1
            "BLUETRUM_V2" -> BLUETRUM_V2
            "TTGK_INFO" -> TTGK_INFO
            "NICEHCK_ORIGIN_BT" -> NICEHCK_ORIGIN_BT
            "MOONDROP_SPV" -> MOONDROP_SPV
            "MOONDROP_COMTURE" -> MOONDROP_COMTURE
            "MOONDROP_FREEMAN3" -> MOONDROP_FREEMAN3
            "MOONDROP_BLE_SOURCE" -> MOONDROP_BLE_SOURCE
            "MOONDROP_AIROHA" -> MOONDROP_AIROHA
            "MOONDROP_JIELI_RCSP" -> MOONDROP_JIELI_RCSP
            "MOONDROP_WMIC" -> MOONDROP_WMIC
            "QUALCOMM_GAIA_V3" -> QUALCOMM_GAIA_V3
            else -> CB
        }
    }
}

enum class BalanceEncoding {
    AUTO,
    WALKPLAY_BE_MAP,
    Q8_8_LE_AT_5
}

data class NamedOption(
    val id: Int,
    val label: String
)

enum class ControlKind {
    SWITCH,
    SLIDER,
    SINGLE_CHOICE,
    BITMASK,
    DUAL_SLIDER_SWITCH,
    ACTION
}

enum class ControlCategory(val title: String) {
    AUDIO("Audio & Volume"),
    BLUETOOTH("Bluetooth & Connections"),
    DISPLAY("Display & LED"),
    BUTTONS("Buttons & Gestures"),
    POWER("Power & Battery"),
    GENERAL("Device Settings")
}

data class DacSliderField(
    val min: Int = 0,
    val max: Int = 5,
    val step: Int = 1,
    val label: String = ""
)

data class DacControlDescriptor(
    val key: String,
    val label: String,
    val category: ControlCategory,
    val kind: ControlKind,
    val min: Int = 0,
    val max: Int = 100,
    val step: Int = 1,
    val unit: String = "",
    val startText: String = "",
    val options: List<NamedOption> = emptyList(),
    val sliders: List<DacSliderField> = emptyList(),
    val isReadable: Boolean = true,
    val isWritable: Boolean = true,
    val allowLimit: Boolean = false,
    val allowMaxValueChange: Boolean = false
)

data class DacControlValue(
    val intValue: Int = 0,
    val secondaryValue: Int = 0,
    val boolValue: Boolean = false,
    val supportMask: Int = -1,
    val dynamicMax: Int? = null,
    val rawHex: String = ""
)

data class DacProfile(
    val id: String,
    val brand: String,
    val model: String,
    val vendorId: Int,
    val productId: Int? = null,
    val productNameMatch: String? = null,
    val protocol: ProtocolFamily,
    val schemeNo: String? = null,
    val bandCount: Int = 10,
    val sampleRate: Int = 96_000,
    val minGainDb: Double = -12.0,
    val maxGainDb: Double = 12.0,
    val reportId: Int = 0x4B,
    val transport: TransportType = TransportType.USB_HID,
    val supportsSurround3d: Boolean = false,
    val supportsMic: Boolean = false,
    val supportsDacFilter: Boolean = false,
    val fiioDeviceType: Int? = null,
    val source: String = "catalog",
    val compensate2X: Boolean? = null,
    val nonBlockingWrite: Boolean = false,
    val isHeadset: Boolean = false,
    val balanceEncoding: BalanceEncoding = BalanceEncoding.AUTO,
    val btSppUuid: String? = null
)

data class DacCapabilities(
    val maxBands: Int,
    val minGainDb: Double = -12.0,
    val maxGainDb: Double = 12.0,
    val minQ: Double = 0.25,
    val maxQ: Double = 8.0,
    val supportedFilters: List<FilterType> = listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF),
    val sampleRate: Int = 96_000,
    val hasEqSwitch: Boolean = false,
    val eqToggleUsesBypass: Boolean = false,
    val hasGlobalGain: Boolean = true,
    val globalGainRange: ClosedFloatingPointRange<Double> = -12.0..12.0,
    val hasDacFilter: Boolean = false,
    val dacFilterOptions: List<NamedOption> = emptyList(),
    val hasOutputMode: Boolean = false,
    val outputModeOptions: List<NamedOption> = emptyList(),
    val hasWorkMode: Boolean = false,
    val workModeOptions: List<NamedOption> = emptyList(),
    val hasVolume: Boolean = false,
    val maxVolume: Int = 60,
    val hasChannelBalance: Boolean = false,
    val channelBalanceMax: Int = 12,
    val hasMicGain: Boolean = false,
    val micGainRange: IntRange = -15..15,
    val hasMicMonitor: Boolean = false,
    val hasMicEnc: Boolean = false,
    val hasMicSwitch: Boolean = false,
    val hasUacSwitch: Boolean = false,
    val uacOptions: List<NamedOption> = emptyList(),
    val hasLedControl: Boolean = false,
    val ledOptions: List<NamedOption> = emptyList(),
    val hasSurround3d: Boolean = false,
    val hasBattery: Boolean = false,
    val presets: List<NamedOption> = emptyList(),
    val uac1Presets: List<NamedOption> = emptyList(),
    val presetCategories: Map<Int, String> = emptyMap(),
    val writablePresetIds: Set<Int> = emptySet(),
    val bypassPresetId: Int? = null,
    val supportsRamRefresh: Boolean = true,
    val supportsFlashSave: Boolean = true,
    val extraControls: List<DacControlDescriptor> = emptyList(),
    val canRenameDevice: Boolean = false,
    val deviceNameMaxBytes: Int = 24,
    val canRenamePresets: Boolean = false,
    val presetNameMaxChars: Int = 8,
    val clearPairingCmd: Int = 0,
    val resetDefaultCmd: Int = 0,
    val closeDeviceCmd: Int = 0,
    val btResetDefaultCmd: Int = 0,
    val systemStandbyCmd: Int = 0
) {
    fun activePresetsForUac(uacMode: Int): List<NamedOption> {
        if (uac1Presets.isEmpty()) return presets
        val isUac1 = uacOptions.firstOrNull { it.id == uacMode }?.label?.contains("1") == true ||
            (uacOptions.isEmpty() && uacMode == 0)
        return if (isUac1) uac1Presets else presets
    }
}

data class DacDeviceState(
    val firmwareVersion: String? = null,
    val eqEnabled: Boolean = true,
    val activePresetId: Int = 0,
    val globalGainDb: Double = 0.0,
    val bands: List<EqBand> = DefaultBands.flatBands(10),
    val dacFilterIndex: Int = 0,
    val outputModeIndex: Int = 0,
    val workModeIndex: Int = 0,
    val volume: Int = 0,
    val maxVolume: Int = 60,
    val channelBalance: Int = 0,
    val micGainDb: Int = 0,
    val micMonitorEnabled: Boolean = false,
    val micEncEnabled: Boolean = false,
    val micSwitchEnabled: Boolean = true,
    val uacMode: Int = 1,
    val surround3dEnabled: Boolean = false,
    val reverbLevel: Int = 0,
    val ledMode: Int = 0,
    val batteryPercent: Int? = null,
    val bluetoothCodec: String? = null,
    val deviceName: String? = null,
    val customPresetNames: Map<Int, String> = emptyMap(),
    val extraControlValues: Map<String, DacControlValue> = emptyMap(),
    val diagnosticNote: String? = null
)
