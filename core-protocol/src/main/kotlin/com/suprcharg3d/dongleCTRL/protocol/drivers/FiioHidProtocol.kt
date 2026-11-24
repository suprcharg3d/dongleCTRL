package com.suprcharg3d.dongleCTRL.protocol.drivers

import com.suprcharg3d.dongleCTRL.dsp.CrcUtils
import com.suprcharg3d.dongleCTRL.dsp.DefaultBands
import com.suprcharg3d.dongleCTRL.dsp.EqBand
import com.suprcharg3d.dongleCTRL.dsp.FilterType
import com.suprcharg3d.dongleCTRL.protocol.BytePack
import com.suprcharg3d.dongleCTRL.protocol.DacCapabilities
import com.suprcharg3d.dongleCTRL.protocol.DacControlValue
import com.suprcharg3d.dongleCTRL.protocol.DacDeviceState
import com.suprcharg3d.dongleCTRL.protocol.DacProfile
import com.suprcharg3d.dongleCTRL.protocol.DacProtocol
import com.suprcharg3d.dongleCTRL.protocol.DacTransport
import com.suprcharg3d.dongleCTRL.protocol.FiioDeviceSchema
import com.suprcharg3d.dongleCTRL.protocol.FiioSchemaCatalog
import com.suprcharg3d.dongleCTRL.protocol.NamedOption
import com.suprcharg3d.dongleCTRL.protocol.ProtocolFamily
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Universal FiiO USB HID Control driver (0xAA 0x0A / 0xBB 0x0B) driven by [FiioSchemaCatalog].
 * Supports KA11/13/15/17, JA11, QX11/13, FP3, FX17, LS-TC2, SNOWSKY MELODY/TINY/MAGE,
 * OAK NANO, JadeAudio JIEZI, and USB PEQ mode on BTR13/15/17/K13 R2R/BR15 R2R/RETRO NANO.
 */
class FiioHidProtocol(
    private val variant: ProtocolFamily = ProtocolFamily.FIIO_HID,
    private val schemas: FiioSchemaCatalog? = null
) : DacProtocol {

    override val family: ProtocolFamily = variant
    override val displayName: String = when (variant) {
        ProtocolFamily.FIIO_V2 -> "FiiO PEQ V2 HID (0xAA/0xBB)"
        ProtocolFamily.FIIO_GIGAWIT -> "FiiO GigaWit Bridge (0xFF)"
        else -> "FiiO HID Control (0xAA/0xBB)"
    }

    private var seq = 0

    // Remember the last known preset / non-bypass preset so toggling Hardware EQ OFF -> ON works reliably.
    @Volatile
    private var lastKnownPresetId: Int? = null
    @Volatile
    private var lastNonBypassPresetId: Int? = null
    @Volatile
    private var lastKa5VolCtrl: Int = 0
    @Volatile
    private var lastKa1RegEa: Int = 0
    @Volatile
    private var ka13Seq: Int = 1
    @Volatile
    private var lastKnownStepMax: Int = 60

    private fun effectiveUsbPeqSchema(schema: FiioDeviceSchema): FiioDeviceSchema {
        if (!schema.usbOnlyPeq) return schema
        return schema.copy(
            parseRulerType = 0,
            eqSwitchSupport = schema.eqSwitchSupport || schema.eqBandSupport,
            eqSwitchGetCmd = CMD_PEQ_SWITCH,
            eqSwitchSetCmd = CMD_PEQ_SWITCH,
            eqSwitchRuler = 0,
            eqPresetSupport = true,
            eqPresetGetCmd = CMD_PEQ_PRE,
            eqPresetSetCmd = CMD_PEQ_PRE,
            eqBandSupport = true,
            eqBandGetCmd = CMD_PEQ_PARAMS,
            eqBandSetCmd = CMD_PEQ_PARAMS,
            eqBandSupportBatch = false,
            eqBandBatchGetCmd = 0,
            eqBandBatchSetCmd = 0,
            eqSaveSupport = schema.eqSaveSupport || schema.eqBandSupport,
            eqSaveSetCmd = CMD_PEQ_SAVE,
            eqResetSetCmd = CMD_RESET_PRE,
            eqResetRuler = 0,
            masterGainSupport = schema.masterGainSupport || schema.eqBandSupport,
            masterGainGetCmd = CMD_GLOBAL_GAIN,
            masterGainSetCmd = CMD_GLOBAL_GAIN,
            masterGainSupportBatch = false,
            editEqNameGetCmd = if (schema.editEqNameSupport) CMD_PEQ_NAME else 0,
            editEqNameSetCmd = if (schema.editEqNameSupport) CMD_PEQ_NAME else 0,
            editEqNameSupportBatch = false
        )
    }

    private fun isGigaWitBridge(): Boolean = variant == ProtocolFamily.FIIO_GIGAWIT

    private fun schemaFor(profile: DacProfile): FiioDeviceSchema? =
        schemas?.let { cat ->
            profile.fiioDeviceType?.let { cat.findByDeviceType(it) }
                ?: cat.matchName(profile.model)
                ?: cat.matchName(profile.productNameMatch)
        }

    override fun buildCapabilities(profile: DacProfile): DacCapabilities {
        val schema = schemaFor(profile)
        if (isGigaWitBridge()) {
            return DacCapabilities(
                maxBands = profile.bandCount.coerceIn(5, 10),
                minGainDb = profile.minGainDb,
                maxGainDb = profile.maxGainDb,
                minQ = 0.25,
                maxQ = 8.0,
                supportedFilters = listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF),
                sampleRate = 48_000,
                hasEqSwitch = false,
                hasGlobalGain = false,
                hasLedControl = true,
                ledOptions = listOf(NamedOption(0, "Off"), NamedOption(1, "Low"), NamedOption(2, "High")),
                canRenameDevice = true,
                deviceNameMaxBytes = 32,
                clearPairingCmd = GIGA_CMD_CLEAR_PAIRED,
                resetDefaultCmd = GIGA_CMD_FACTORY_RESET,
                closeDeviceCmd = GIGA_CMD_REBOOT,
                supportsRamRefresh = false,
                supportsFlashSave = false
            )
        }
        if (schema == null) {
            return DacCapabilities(
                maxBands = profile.bandCount.coerceIn(5, 31),
                minGainDb = profile.minGainDb,
                maxGainDb = profile.maxGainDb,
                minQ = 0.25,
                maxQ = 8.0,
                supportedFilters = listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF),
                sampleRate = 48_000,
                hasEqSwitch = true,
                hasGlobalGain = true,
                globalGainRange = profile.minGainDb..profile.maxGainDb,
                hasDacFilter = false,
                hasVolume = false,
                hasChannelBalance = false,
                hasLedControl = false,
                presets = DEFAULT_PRESETS,
                writablePresetIds = setOf(160, 161, 162),
                supportsRamRefresh = false,
                supportsFlashSave = false
            )
        }

        val usbOnlyPeq = schema.usbOnlyPeq
        val peqSchema = effectiveUsbPeqSchema(schema)
        val cbt = schema.commandBuilderType ?: 101
        val supportsPeq = (peqSchema.eqPresetSupport || peqSchema.eqBandSupport) &&
            cbt !in setOf(61448, 61449, 61450, 61452, 61454)

        val volCtrl = if (!usbOnlyPeq) schema.findControl("vol_output", "device_volume", "device_volume_nui") else null
        val balCtrl = if (!usbOnlyPeq) schema.findControl("channel_balance") else null
        val filterCtrl = if (!usbOnlyPeq) schema.findControl("filter", "utws17_filter") else null
        val gainCtrl = if (!usbOnlyPeq) schema.findControl("gain", "gain_level") else null
        val ampCtrl = if (!usbOnlyPeq) schema.findControl("boost_bal_out_mode", "dac_work_mode", "output_estimation") else null
        val uacCtrl = if (!usbOnlyPeq) schema.findControl("uac_version", "uac_version_nui", "uac", "uac_1_0") else null
        val micSwCtrl = if (!usbOnlyPeq) schema.findControl("mic_detect", "microphone_switch") else null
        val micVolCtrl = if (!usbOnlyPeq) schema.findControl("mic_vol", "microphone_volume") else null
        val micMonCtrl = if (!usbOnlyPeq) schema.findControl("mic_monitor", "mic_monitor_volume", "microphone_listen_title") else null
        val ledCtrl = if (!usbOnlyPeq) schema.findControl("led_light", "Indicator_light_switch", "indicator_light_brightness", "led_title", "indicaot") else null

        val presetOptions = if (schema.presets.isNotEmpty()) {
            schema.presets.map { NamedOption(it.id, it.label) }
        } else {
            DEFAULT_PRESETS
        }
        val uac1Defs = if (schema.parseRulerType == 12) schema.presetsForUac(isUac1 = true) else emptyList()
        val uac1PresetOptions = uac1Defs.map { NamedOption(it.id, it.label) }
        val presetCategories = (schema.presets + uac1Defs)
            .mapNotNull { p -> p.category?.let { p.id to it } }
            .toMap()

        val supportsPeqBands = supportsPeq && peqSchema.eqBandSupport && schema.bandCount > 0
        val hasDirectSwitch = peqSchema.eqSwitchSupport && peqSchema.eqSwitchSetCmd > 0
        val effectiveEqSwitch = supportsPeq && (hasDirectSwitch || schema.bypassPresetId != null)
        val filterOpts = filterCtrl?.options?.takeIf { it.isNotEmpty() } ?: DEFAULT_DAC_FILTERS

        return DacCapabilities(
            maxBands = if (supportsPeqBands) schema.bandCount.coerceIn(5, 31) else 0,
            minGainDb = schema.gainMin,
            maxGainDb = schema.gainMax,
            minQ = 0.25,
            maxQ = 8.0,
            supportedFilters = if (supportsPeqBands) peqSchema.supportedFilterTypes() else emptyList(),
            sampleRate = 48_000,
            hasEqSwitch = effectiveEqSwitch,
            eqToggleUsesBypass = !hasDirectSwitch && schema.bypassPresetId != null,
            hasGlobalGain = supportsPeq && peqSchema.masterGainSupport,
            globalGainRange = schema.gainMin..schema.gainMax,
            hasDacFilter = filterCtrl != null,
            dacFilterOptions = filterOpts,
            hasOutputMode = ampCtrl != null && ampCtrl.options.isNotEmpty(),
            outputModeOptions = ampCtrl?.options.orEmpty(),
            hasWorkMode = gainCtrl != null && gainCtrl.options.isNotEmpty(),
            workModeOptions = gainCtrl?.options.orEmpty(),
            hasVolume = volCtrl != null,
            maxVolume = volCtrl?.max ?: 60,
            hasChannelBalance = balCtrl != null,
            channelBalanceMax = balCtrl?.max ?: 12,
            hasMicGain = micVolCtrl != null,
            micGainRange = (micVolCtrl?.min ?: 0)..(micVolCtrl?.max ?: 24),
            hasMicMonitor = micMonCtrl != null,
            hasMicSwitch = micSwCtrl != null,
            hasUacSwitch = uacCtrl != null && uacCtrl.options.isNotEmpty(),
            uacOptions = uacCtrl?.options.orEmpty(),
            hasLedControl = ledCtrl != null,
            ledOptions = ledCtrl?.options?.takeIf { it.isNotEmpty() } ?: DEFAULT_LED_OPTIONS,
            hasSurround3d = profile.supportsSurround3d,
            presets = if (supportsPeq) presetOptions else emptyList(),
            uac1Presets = if (supportsPeq) uac1PresetOptions else emptyList(),
            presetCategories = presetCategories,
            writablePresetIds = if (supportsPeq) schema.customPresetIds else emptySet(),
            bypassPresetId = schema.bypassPresetId,
            canRenamePresets = supportsPeq && schema.editEqNameSupport && peqSchema.editEqNameSetCmd > 0,
            presetNameMaxChars = 8,
            supportsRamRefresh = false,
            supportsFlashSave = supportsPeq && peqSchema.eqSaveSupport,
            extraControls = if (usbOnlyPeq) emptyList() else FiioSchemaControlsHelper.buildExtraControls(schema),
            canRenameDevice = !usbOnlyPeq && schema.editNameSetCmd > 0,
            deviceNameMaxBytes = schema.editNameMaxBytes.coerceIn(8, 32),
            clearPairingCmd = if (usbOnlyPeq) 0 else schema.clearPairingCmd,
            resetDefaultCmd = if (usbOnlyPeq) 0 else schema.resetDefaultCmd,
            closeDeviceCmd = if (usbOnlyPeq) 0 else schema.closeDeviceCmd,
            btResetDefaultCmd = if (usbOnlyPeq) 0 else schema.btResetDefaultCmd,
            systemStandbyCmd = if (usbOnlyPeq) 0 else schema.systemStandbyCmd
        )
    }

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        transport.drainInput()
        val schema = schemaFor(profile)
        val peqSchema = schema?.let(::effectiveUsbPeqSchema)
        val caps = buildCapabilities(profile)
        val rid = resolveReportId(transport, profile)
        if (isGigaWitBridge()) {
            val fwStr = usbRequest(transport, schema, rid, GIGA_CMD_FW)?.let { parseFirmwareVersion(it) }
            val devName = usbRequest(transport, schema, rid, GIGA_CMD_GET_NAME)?.let {
                it.takeWhile { b -> b != 0.toByte() }.toByteArray().decodeToString().trim().ifEmpty { null }
            }
            val brightness = usbRequest(transport, schema, rid, GIGA_CMD_GET_BRIGHTNESS)
                ?.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            return DacDeviceState(
                firmwareVersion = fwStr,
                ledMode = brightness,
                deviceName = devName,
                bands = DefaultBands.flatBands(caps.maxBands),
                diagnosticNote = "FiiO GigaWit USB Bridge (0xFF 0x03)"
            )
        }
        val cbt = schema?.commandBuilderType ?: 101
        val cmdDelay = (schema?.deviceCommandInterval ?: 0).toLong().coerceIn(0L, 100L)

        // KA5 vol_control determines 0..120 (0) vs 0..60 (non-0) volume mapping
        if (cbt == 61449) {
            usbRequest(transport, schema, rid, 13)?.let {
                lastKa5VolCtrl = it.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            }
        }

        val fwCmd = schema?.deviceVersionCmd?.takeIf { it > 0 } ?: CMD_FIRMWARE_VERSION
        val fwPayload = usbRequest(transport, schema, rid, fwCmd)
        val fwStr = fwPayload?.let { parseFirmwareVersion(it) }

        val supportsPeq = (peqSchema?.eqPresetSupport != false || peqSchema.eqBandSupport) &&
            cbt !in setOf(61448, 61449, 61450, 61452, 61454)

        val preCmd = peqSchema?.eqPresetGetCmd ?: if (variant == ProtocolFamily.FIIO_V2) CMD_PEQ_PRE_V2 else CMD_PEQ_PRE
        val activePreset = if (supportsPeq && preCmd > 0) {
            usbRequest(transport, peqSchema, rid, preCmd)?.let { p ->
                when {
                    p.size >= 2 -> BytePack.getU16Be(p, 0)
                    p.isNotEmpty() -> p[0].toInt() and 0xFF
                    else -> peqSchema?.firstCustomPresetId ?: 160
                }
            } ?: (peqSchema?.firstCustomPresetId ?: 160)
        } else {
            peqSchema?.firstCustomPresetId ?: 160
        }

        lastKnownPresetId = activePreset
        if (caps.bypassPresetId == null || activePreset != caps.bypassPresetId) {
            lastNonBypassPresetId = activePreset
        }

        val eqEnabled = if (!supportsPeq) {
            true
        } else if (peqSchema?.eqSwitchSupport == true) {
            usbRequest(transport, peqSchema, rid, peqSchema.eqSwitchGetCmd)?.let {
                FiioPeqCodec.decodeEqSwitch(it, peqSchema.eqSwitchRuler)
            } ?: true
        } else if (caps.bypassPresetId != null) {
            activePreset != caps.bypassPresetId
        } else {
            true
        }

        val globalGain = if (supportsPeq && caps.hasGlobalGain && peqSchema != null) {
            val mgPayload = FiioPeqCodec.buildQueryMasterGainPayload(peqSchema, activePreset)
            usbRequest(transport, peqSchema, rid, peqSchema.masterGainGetCmd, mgPayload)?.let {
                FiioPeqCodec.decodeMasterGain(it, peqSchema.parseRulerType)
            }?.coerceIn(caps.minGainDb, caps.maxGainDb) ?: 0.0
        } else 0.0

        val volMax = if (caps.hasVolume) {
            if (cbt == 61449) {
                if (lastKa5VolCtrl == 0) 120 else 60
            } else if (cbt in setOf(61448, 61452, 61454)) {
                caps.maxVolume
            } else {
                usbRequest(transport, schema, rid, CMD_VOL_MAX)?.firstOrNull()?.toInt()?.and(0xFF)
                    ?.coerceIn(1, 120) ?: caps.maxVolume
            }
        } else caps.maxVolume

        val vol = if (caps.hasVolume) {
            val volCtrl = schema?.findControl("vol_output", "device_volume", "device_volume_nui")
            val volCmd = volCtrl?.getCmd ?: CMD_VOL_OUTPUT
            usbRequest(transport, schema, rid, volCmd)?.let {
                if (volCtrl != null) FiioSchemaControlsHelper.decodeResponse(volCtrl, it, isUsb = true).intValue
                else it.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            }?.coerceIn(0, volMax) ?: 0
        } else 0

        val filterCtrl = schema?.findControl("filter", "utws17_filter")
        val filterIdx = if (caps.hasDacFilter) {
            val fCmd = filterCtrl?.getCmd ?: CMD_FILTER_MODE
            usbRequest(transport, schema, rid, fCmd)?.let {
                if (filterCtrl != null) FiioSchemaControlsHelper.decodeResponse(filterCtrl, it, isUsb = true).intValue
                else it.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            } ?: 0
        } else 0

        val gainCtrl = schema?.findControl("gain", "gain_level")
        val gainModeIdx = if (caps.hasWorkMode && (gainCtrl?.getCmd ?: 0) > 0) {
            usbRequest(transport, schema, rid, gainCtrl!!.getCmd)?.let {
                FiioSchemaControlsHelper.decodeResponse(gainCtrl, it, isUsb = true).intValue
            } ?: 0
        } else 0

        val ampCtrl = schema?.findControl("boost_bal_out_mode", "dac_work_mode", "output_estimation")
        val ampModeIdx = if (caps.hasOutputMode && (ampCtrl?.getCmd ?: 0) > 0) {
            usbRequest(transport, schema, rid, ampCtrl!!.getCmd)?.let {
                FiioSchemaControlsHelper.decodeResponse(ampCtrl, it, isUsb = true).intValue
            } ?: 0
        } else 0

        val uacCtrl = schema?.findControl("uac_version", "uac_version_nui", "uac", "uac_1_0")
        val uacMode = if (caps.hasUacSwitch) {
            val uCmd = uacCtrl?.getCmd ?: CMD_UAC_VERSION
            usbRequest(transport, schema, rid, uCmd)?.let {
                if (uacCtrl != null) FiioSchemaControlsHelper.decodeResponse(uacCtrl, it, isUsb = true).intValue
                else it.firstOrNull()?.toInt()?.and(0xFF) ?: 1
            } ?: 1
        } else 1

        val micSwCtrl = schema?.findControl("mic_detect", "microphone_switch")
        val micSwitch = if (caps.hasMicSwitch) {
            val mCmd = micSwCtrl?.getCmd ?: CMD_MIC_SWITCH
            usbRequest(transport, schema, rid, mCmd)?.let {
                if (micSwCtrl != null) FiioSchemaControlsHelper.decodeResponse(micSwCtrl, it, isUsb = true).boolValue
                else (it.firstOrNull()?.toInt()?.and(0xFF) ?: 0) != 0
            } ?: false
        } else false

        val micVolCtrl = schema?.findControl("mic_vol", "microphone_volume")
        val micGain = if (caps.hasMicGain) {
            val mvCmd = micVolCtrl?.getCmd ?: CMD_MIC_VOL
            usbRequest(transport, schema, rid, mvCmd)?.let {
                if (micVolCtrl != null) FiioSchemaControlsHelper.decodeResponse(micVolCtrl, it, isUsb = true).intValue
                else it.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            } ?: 0
        } else 0

        val balCtrl = schema?.findControl("channel_balance")
        val balance = if (caps.hasChannelBalance) {
            val balCmd = balCtrl?.getCmd ?: CMD_VOL_BALANCE
            usbRequest(transport, schema, rid, balCmd)?.let { p ->
                FiioSchemaControlsHelper.decodeChannelBalance(p, balCtrl?.parseRulerType ?: 0, isUsb = true)
            } ?: 0
        } else 0

        val ledCtrl = schema?.findControl("led_light", "Indicator_light_switch", "indicator_light_brightness", "led_title", "indicaot")
        val ledMode = if (caps.hasLedControl) {
            val lCmd = ledCtrl?.getCmd ?: CMD_LED_LIGHT
            usbRequest(transport, schema, rid, lCmd)?.let {
                if (ledCtrl != null) FiioSchemaControlsHelper.decodeResponse(ledCtrl, it, isUsb = true).intValue
                else it.firstOrNull()?.toInt()?.and(0xFF) ?: 1
            } ?: 1
        } else 0

        val extraValuesRaw = readExtraControlsHid(transport, schema, rid)
        val (extraValues, computedVolMax) = if (schema != null) {
            FiioSchemaControlsHelper.recomputeVolumeConstraints(schema, extraValuesRaw, volMax)
        } else {
            extraValuesRaw to volMax
        }
        val finalVolMax = if (schema != null && schema.findControl("vol_control", "vol_limit") != null) {
            computedVolMax
        } else {
            volMax
        }
        lastKnownStepMax = if (schema != null) {
            FiioSchemaControlsHelper.resolveStepMax(
                schema,
                extraValues["vol_control"]?.intValue,
                volMax
            )
        } else {
            volMax
        }

        val devName = if (schema != null && !schema.usbOnlyPeq && schema.editNameGetCmd > 0) {
            usbRequest(transport, schema, rid, schema.editNameGetCmd)?.let { p ->
                FiioSchemaControlsHelper.decodeDeviceName(p, schema.editNameRuler)
            }
        } else null

        val bands = if (supportsPeq && caps.maxBands > 0) {
            readPeqBandsHid(transport, peqSchema, rid, activePreset, caps, cmdDelay)
        } else {
            DefaultBands.flatBands(caps.maxBands)
        }

        val customNames = if (supportsPeq && caps.canRenamePresets && peqSchema != null) {
            readPresetNamesHid(transport, peqSchema, rid, caps, cmdDelay)
        } else emptyMap()

        return DacDeviceState(
            firmwareVersion = fwStr,
            eqEnabled = eqEnabled,
            activePresetId = activePreset,
            globalGainDb = globalGain,
            bands = bands,
            customPresetNames = customNames,
            dacFilterIndex = filterIdx,
            outputModeIndex = ampModeIdx,
            workModeIndex = gainModeIdx,
            volume = vol.coerceIn(0, finalVolMax),
            maxVolume = finalVolMax,
            channelBalance = balance.coerceIn(-caps.channelBalanceMax, caps.channelBalanceMax),
            micGainDb = micGain,
            micSwitchEnabled = micSwitch,
            uacMode = uacMode,
            ledMode = ledMode,
            deviceName = devName,
            extraControlValues = extraValues
        )
    }

    private suspend fun readPresetNamesHid(
        transport: DacTransport,
        schema: FiioDeviceSchema,
        rid: Int,
        caps: DacCapabilities,
        cmdDelayMs: Long
    ): Map<Int, String> {
        val getCmd = schema.editEqNameGetCmd
        if (getCmd <= 0) return emptyMap()
        if (schema.editEqNameSupportBatch) {
            val out = LinkedHashMap<Int, String>()
            val writableList = caps.writablePresetIds.toList()
            val ranges = FiioPeqCodec.encodeQueryBatchEqNamesPayloads(schema, caps.writablePresetIds)
            var anyResponded = false
            for ((i, rangeReq) in ranges.withIndex()) {
                if (cmdDelayMs > 0 && i > 0) delay(cmdDelayMs)
                val first = usbRequest(transport, schema, rid, getCmd, rangeReq) ?: continue
                val expectedCount = ((rangeReq[1].toInt() and 0xFF) - (rangeReq[0].toInt() and 0xFF) + 1).coerceAtLeast(1)
                val coveredSlots = HashSet<Int>()
                if (FiioPeqCodec.accumulateBatchEqNamesFrame(first, writableList, schema.parseRulerType, out, coveredSlots)) {
                    anyResponded = true
                }
                var extraTries = 0
                while (coveredSlots.size < expectedCount && extraTries < expectedCount + 4) {
                    extraTries++
                    val rx = transport.receive(
                        expectedSize = transport.descriptor.hidReportSize.coerceAtLeast(64),
                        timeoutMs = 150
                    ) ?: break
                    val startIdx = findResponseFrameStart(rx, getCmd)
                    val payload = if (startIdx >= 0) extractPayload(rx.copyOfRange(startIdx, rx.size)) else null
                    if (payload != null) {
                        if (FiioPeqCodec.accumulateBatchEqNamesFrame(payload, writableList, schema.parseRulerType, out, coveredSlots)) {
                            anyResponded = true
                        }
                    }
                }
            }
            if (anyResponded) return out
        }
        val out = LinkedHashMap<Int, String>()
        for ((i, slotId) in caps.writablePresetIds.withIndex()) {
            if (cmdDelayMs > 0 && i > 0) delay(cmdDelayMs)
            val req = FiioPeqCodec.encodeQueryEditEqNamePayload(schema, slotId)
            val raw = usbRequest(transport, schema, rid, getCmd, req) ?: continue
            FiioPeqCodec.decodeEditEqNamePayload(schema, slotId, raw)?.let { (id, text) ->
                if (text.isNotEmpty()) out[id] = text
            }
        }
        return out
    }

    private suspend fun readPeqBandsHid(
        transport: DacTransport,
        schema: FiioDeviceSchema?,
        rid: Int,
        activePreset: Int,
        caps: DacCapabilities,
        cmdDelayMs: Long
    ): List<EqBand> {
        if (schema == null) {
            val countResp = usbRequest(transport, null, rid, CMD_PEQ_COUNT)
            val hwBandCount = countResp?.firstOrNull()?.toInt()?.and(0xFF)?.takeIf { it in 1..31 } ?: caps.maxBands
            return DefaultBands.flatBands(hwBandCount).mapIndexed { idx, defaultBand ->
                val p = usbRequest(transport, null, rid, CMD_PEQ_PARAMS, byteArrayOf(idx.toByte()))
                if (p != null && p.size >= 8) {
                    val gain = (BytePack.getI16Be(p, 1) / 10.0).coerceIn(caps.minGainDb, caps.maxGainDb)
                    val freq = BytePack.getU16Be(p, 3)
                    val q = (BytePack.getU16Be(p, 5) / 100.0).coerceIn(caps.minQ, caps.maxQ)
                    val fType = FilterType.fromFiioCode(p[7].toInt() and 0xFF)
                    if (freq in 10..24000) EqBand(idx, freq, gain, q, fType) else defaultBand
                } else defaultBand
            }
        }

        if (schema.eqBandSupportBatch && schema.eqBandBatchGetCmd > 0) {
            val batchReq = FiioPeqCodec.buildQueryBatchBandsPayload(schema, activePreset)
            val batchResp = usbRequest(transport, schema, rid, schema.eqBandBatchGetCmd, batchReq)
            val decodedBatch = batchResp?.let { FiioPeqCodec.decodeBatchBands(it, schema) }
            if (!decodedBatch.isNullOrEmpty()) return decodedBatch
        }

        val countResp = usbRequest(transport, schema, rid, CMD_PEQ_COUNT)
        val hwBandCount = countResp?.firstOrNull()?.toInt()?.and(0xFF)?.takeIf { it in 1..31 } ?: schema.bandCount.coerceIn(1, 31)
        val baseList = FiioPeqCodec.flatBandsForSchema(schema.copy(bandCount = hwBandCount))
        val bandCmd = schema.eqBandGetCmd.takeIf { it > 0 } ?: CMD_PEQ_PARAMS
        return baseList.mapIndexed { idx, defaultBand ->
            if (cmdDelayMs > 0 && idx > 0) delay(cmdDelayMs)
            val qPayload = FiioPeqCodec.buildQuerySingleBandPayload(schema, idx, activePreset)
            val raw = usbRequest(transport, schema, rid, bandCmd, qPayload)
            raw?.let { FiioPeqCodec.decodeSingleBand(it, schema, idx) } ?: defaultBand
        }
    }

    private suspend fun readExtraControlsHid(
        transport: DacTransport,
        schema: FiioDeviceSchema?,
        rid: Int
    ): Map<String, DacControlValue> {
        val extraValues = LinkedHashMap<String, DacControlValue>()
        if (schema != null && !schema.usbOnlyPeq) {
            for (ctrl in schema.controls) {
                if (ctrl.key.isBlank() || FiioSchemaControlsHelper.isPrimaryControl(ctrl.key) || ctrl.getCmd <= 0) continue
                val pay = usbRequest(transport, schema, rid, ctrl.getCmd) ?: continue
                var decoded = FiioSchemaControlsHelper.decodeResponse(ctrl, pay, isUsb = true)
                if (ctrl.supportCmd > 0) {
                    usbRequest(transport, schema, rid, ctrl.supportCmd)?.let { sup ->
                        decoded = decoded.copy(supportMask = FiioSchemaControlsHelper.parseUint(sup))
                    }
                }
                if (ctrl.queryCmd > 0) {
                    usbRequest(transport, schema, rid, ctrl.queryCmd)?.let { q ->
                        decoded = decoded.copy(supportMask = FiioSchemaControlsHelper.parseUint(q))
                    }
                }
                extraValues[ctrl.key] = decoded
            }
        }
        return extraValues
    }

    override suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        if (isGigaWitBridge()) return false
        val schema = schemaFor(profile)?.let(::effectiveUsbPeqSchema)
        val rid = resolveReportId(transport, profile)
        if (schema == null) {
            val gain10 = (band.gainDb.coerceIn(profile.minGainDb, profile.maxGainDb) * 10.0).roundToInt()
            val freq = band.frequencyHz.coerceIn(10, 24000)
            val q100 = (band.q * 100.0).roundToInt().coerceIn(25, 800)
            val payload = ByteArray(8)
            payload[0] = band.index.toByte()
            BytePack.u16Be(payload, 1, gain10)
            BytePack.u16Be(payload, 3, freq)
            BytePack.u16Be(payload, 5, q100)
            payload[7] = band.filterType.fiioCode.toByte()
            return usbSend(transport, null, rid, CMD_PEQ_PARAMS, payload)
        }
        val useBatch = schema.eqBandSupportBatch && schema.eqBandBatchSetCmd > 0
        val cmd = if (useBatch) schema.eqBandBatchSetCmd else schema.eqBandSetCmd.takeIf { it > 0 } ?: CMD_PEQ_PARAMS
        val payload = FiioPeqCodec.encodeSingleBand(schema, band, presetId, batchSet = useBatch)
        return usbSend(transport, schema, rid, cmd, payload)
    }

    override suspend fun writeAllEqBands(
        transport: DacTransport,
        profile: DacProfile,
        bands: List<EqBand>,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        if (isGigaWitBridge()) return false
        val schema = schemaFor(profile)?.let(::effectiveUsbPeqSchema)
        val interval = (schema?.deviceCommandInterval ?: 0).toLong().coerceAtLeast(25L)
        if (schema == null || schema.masterGainSupport) {
            if (!setGlobalGain(transport, profile, globalGainDb)) return false
            delay(interval)
        }
        for (band in bands) {
            if (!writeEqBand(transport, profile, band, globalGainDb, presetId)) return false
            delay(interval)
        }
        return true
    }

    override suspend fun saveToFlash(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean {
        if (isGigaWitBridge()) return false
        val schema = schemaFor(profile)?.let(::effectiveUsbPeqSchema)
        val saveCmd = schema?.eqSaveSetCmd?.takeIf { it > 0 } ?: CMD_PEQ_SAVE
        val rid = resolveReportId(transport, profile)
        val targetId = if (schema != null && schema.customPresetIds.isNotEmpty() && presetId !in schema.customPresetIds) {
            schema.firstCustomPresetId
        } else {
            presetId
        }
        val data = if (schema != null) {
            FiioPeqCodec.buildSavePayload(schema, targetId) ?: return false
        } else if (targetId > 255) {
            ByteArray(2).also { BytePack.u16Be(it, 0, targetId) }
        } else {
            byteArrayOf((targetId and 0xFF).toByte())
        }
        val ok = usbSend(transport, schema, rid, saveCmd, data)
        delay(80)
        return ok
    }

    override suspend fun resetEq(transport: DacTransport, profile: DacProfile): Boolean {
        if (isGigaWitBridge()) return false
        val schema = schemaFor(profile)?.let(::effectiveUsbPeqSchema)
        val resetCmd = schema?.eqResetSetCmd?.takeIf { it > 0 } ?: CMD_RESET_PRE
        val rid = resolveReportId(transport, profile)
        val defaultSlot = lastKnownPresetId ?: schema?.firstCustomPresetId ?: 160
        val payload = if (schema != null) {
            FiioPeqCodec.buildResetPayload(schema, defaultSlot) ?: return false
        } else {
            ByteArray(2).also { BytePack.u16Be(it, 0, defaultSlot) }
        }
        return usbSend(transport, schema, rid, resetCmd, payload)
    }

    override suspend fun setEqEnabled(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean {
        if (isGigaWitBridge()) return false
        val schema = schemaFor(profile)?.let(::effectiveUsbPeqSchema)
        val rid = resolveReportId(transport, profile)
        val bypassId = schema?.bypassPresetId

        if (schema == null || schema.eqSwitchSupport) {
            val swCmd = schema?.eqSwitchSetCmd ?: CMD_PEQ_SWITCH
            val payload = FiioPeqCodec.encodeEqSwitch(enabled, schema?.eqSwitchRuler ?: 0)
            return usbSend(transport, schema, rid, swCmd, payload)
        } else if (bypassId != null) {
            val targetPreset = if (enabled) {
                lastNonBypassPresetId?.takeIf { it != bypassId } ?: schema.firstCustomPresetId
            } else {
                bypassId
            }
            return selectPreset(transport, profile, targetPreset)
        }
        return false
    }

    override suspend fun selectPreset(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean {
        if (isGigaWitBridge()) return false
        val schema = schemaFor(profile)?.let(::effectiveUsbPeqSchema)
        val rid = resolveReportId(transport, profile)
        val cmd = schema?.eqPresetSetCmd ?: if (variant == ProtocolFamily.FIIO_V2) CMD_PEQ_PRE_V2 else CMD_PEQ_PRE
        val data = if (presetId > 255 || variant == ProtocolFamily.FIIO_V2) {
            ByteArray(2).also { BytePack.u16Be(it, 0, presetId) }
        } else {
            byteArrayOf((presetId and 0xFF).toByte())
        }
        lastKnownPresetId = presetId
        if (schema?.bypassPresetId == null || presetId != schema.bypassPresetId) {
            lastNonBypassPresetId = presetId
        }
        return usbSend(transport, schema, rid, cmd, data)
    }

    override suspend fun setGlobalGain(transport: DacTransport, profile: DacProfile, gainDb: Double): Boolean {
        if (isGigaWitBridge()) return false
        val schema = schemaFor(profile)?.let(::effectiveUsbPeqSchema)
        val rid = resolveReportId(transport, profile)
        val cmd = schema?.masterGainSetCmd ?: CMD_GLOBAL_GAIN
        val slotId = lastKnownPresetId ?: schema?.firstCustomPresetId ?: 160
        val data = if (schema != null) {
            FiioPeqCodec.encodeMasterGain(schema, gainDb, slotId)
        } else {
            ByteArray(2).also { BytePack.u16Be(it, 0, (gainDb * 10.0).roundToInt()) }
        }
        return usbSend(transport, schema, rid, cmd, data)
    }

    override suspend fun setPresetName(
        transport: DacTransport,
        profile: DacProfile,
        presetId: Int,
        name: String
    ): Boolean {
        if (isGigaWitBridge()) return false
        val schema = schemaFor(profile)?.let(::effectiveUsbPeqSchema) ?: return false
        if (!schema.editEqNameSupport || schema.editEqNameSetCmd <= 0) return false
        val payload = FiioPeqCodec.buildEditEqNamePayload(schema, presetId, name) ?: return false
        val rid = resolveReportId(transport, profile)
        return usbSend(transport, schema, rid, schema.editEqNameSetCmd, payload)
    }

    override suspend fun refreshPresetEqState(
        transport: DacTransport,
        profile: DacProfile,
        currentState: DacDeviceState,
        presetId: Int
    ): DacDeviceState? {
        if (isGigaWitBridge()) return currentState
        val rawSchema = schemaFor(profile)
        val peqSchema = rawSchema?.let(::effectiveUsbPeqSchema)
        val caps = buildCapabilities(profile)
        val rid = resolveReportId(transport, profile)
        val cmdDelay = (rawSchema?.deviceCommandInterval ?: 0).toLong().coerceIn(0L, 100L)

        val eqEnabled = if (peqSchema?.eqSwitchSupport == true && peqSchema.eqSwitchGetCmd > 0) {
            usbRequest(transport, peqSchema, rid, peqSchema.eqSwitchGetCmd)?.let {
                FiioPeqCodec.decodeEqSwitch(it, peqSchema.eqSwitchRuler)
            } ?: currentState.eqEnabled
        } else if (caps.bypassPresetId != null) {
            presetId != caps.bypassPresetId
        } else {
            currentState.eqEnabled
        }

        val globalGain = if (peqSchema != null && caps.hasGlobalGain) {
            val mgPayload = FiioPeqCodec.buildQueryMasterGainPayload(peqSchema, presetId)
            usbRequest(transport, peqSchema, rid, peqSchema.masterGainGetCmd, mgPayload)?.let {
                FiioPeqCodec.decodeMasterGain(it, peqSchema.parseRulerType)
            }?.coerceIn(caps.minGainDb, caps.maxGainDb) ?: currentState.globalGainDb
        } else currentState.globalGainDb

        val bands = readPeqBandsHid(transport, peqSchema, rid, presetId, caps, cmdDelay)
        return currentState.copy(
            eqEnabled = eqEnabled,
            activePresetId = presetId,
            globalGainDb = globalGain,
            bands = bands
        )
    }

    override suspend fun setDacFilter(transport: DacTransport, profile: DacProfile, filterIndex: Int): Boolean {
        val schema = schemaFor(profile)
        if (schema?.usbOnlyPeq == true) return false
        val rid = resolveReportId(transport, profile)
        val ctrl = schema?.findControl("filter", "utws17_filter")
        val cmd = ctrl?.setCmd ?: CMD_FILTER_MODE
        val payload = if (ctrl != null) {
            FiioSchemaControlsHelper.encodePayload(ctrl, DacControlValue(intValue = filterIndex))
        } else {
            byteArrayOf((filterIndex and 0xFF).toByte())
        }
        return usbSend(transport, schema, rid, cmd, payload)
    }

    override suspend fun setOutputMode(transport: DacTransport, profile: DacProfile, modeIndex: Int): Boolean {
        val schema = schemaFor(profile)
        if (schema?.usbOnlyPeq == true) return false
        val ctrl = schema?.findControl("boost_bal_out_mode", "dac_work_mode", "output_estimation") ?: return false
        val rid = resolveReportId(transport, profile)
        val payload = FiioSchemaControlsHelper.encodePayload(ctrl, DacControlValue(intValue = modeIndex))
        return usbSend(transport, schema, rid, ctrl.setCmd, payload)
    }

    override suspend fun setWorkMode(transport: DacTransport, profile: DacProfile, modeIndex: Int): Boolean {
        val schema = schemaFor(profile)
        if (schema?.usbOnlyPeq == true) return false
        val ctrl = schema?.findControl("gain", "gain_level") ?: return false
        val rid = resolveReportId(transport, profile)
        val payload = FiioSchemaControlsHelper.encodePayload(ctrl, DacControlValue(intValue = modeIndex))
        return usbSend(transport, schema, rid, ctrl.setCmd, payload)
    }

    override suspend fun setVolume(transport: DacTransport, profile: DacProfile, volume: Int): Boolean {
        val schema = schemaFor(profile)
        if (schema?.usbOnlyPeq == true) return false
        val rid = resolveReportId(transport, profile)
        val ctrl = schema?.findControl("vol_output", "device_volume", "device_volume_nui")
        val cmd = ctrl?.setCmd ?: CMD_VOL_OUTPUT
        val payload = if (ctrl != null) {
            FiioSchemaControlsHelper.encodePayload(
                ctrl,
                DacControlValue(intValue = volume.coerceIn(0, 120)),
                presetSlotId = lastKnownPresetId ?: 0
            )
        } else {
            byteArrayOf(volume.coerceIn(0, 120).toByte())
        }
        val ok = usbSend(transport, schema, rid, cmd, payload)
        if (ok && ctrl?.parseRulerType == 3 && schema.eqSaveSupport && schema.eqSaveSetCmd > 0) {
            saveToFlash(transport, profile, lastKnownPresetId ?: 0)
        }
        return ok
    }

    override suspend fun setChannelBalance(transport: DacTransport, profile: DacProfile, balance: Int): Boolean {
        val schema = schemaFor(profile)
        if (schema?.usbOnlyPeq == true) return false
        val rid = resolveReportId(transport, profile)
        val balCtrl = schema?.findControl("channel_balance")
        val cmd = balCtrl?.setCmd ?: CMD_VOL_BALANCE
        val payload = FiioSchemaControlsHelper.encodeChannelBalance(
            balance = balance,
            maxAbs = balCtrl?.max ?: 12,
            parseRulerType = balCtrl?.parseRulerType ?: 0,
            isUsb = true
        )
        return usbSend(transport, schema, rid, cmd, payload)
    }

    override suspend fun setMicGain(transport: DacTransport, profile: DacProfile, gainDb: Int): Boolean {
        val schema = schemaFor(profile)
        if (schema?.usbOnlyPeq == true) return false
        val rid = resolveReportId(transport, profile)
        val cmd = schema?.findControl("mic_vol", "microphone_volume")?.setCmd ?: CMD_MIC_VOL
        val clamped = gainDb.coerceIn(0, 24)
        return usbSend(transport, schema, rid, cmd, byteArrayOf(clamped.toByte()))
    }

    override suspend fun setMicMonitor(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean {
        val schema = schemaFor(profile)
        if (schema?.usbOnlyPeq == true) return false
        val rid = resolveReportId(transport, profile)
        val cmd = schema?.findControl("mic_monitor", "mic_monitor_volume", "microphone_listen_title")?.setCmd ?: CMD_MIC_MONITOR_SWITCH
        return usbSend(transport, schema, rid, cmd, byteArrayOf(if (enabled) 1 else 0))
    }

    override suspend fun setMicSwitch(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean {
        val schema = schemaFor(profile)
        if (schema?.usbOnlyPeq == true) return false
        val rid = resolveReportId(transport, profile)
        val cmd = schema?.findControl("mic_detect", "microphone_switch")?.setCmd ?: CMD_MIC_SWITCH
        return usbSend(transport, schema, rid, cmd, byteArrayOf(if (enabled) 1 else 0))
    }

    override suspend fun setUacMode(transport: DacTransport, profile: DacProfile, mode: Int): Boolean {
        val schema = schemaFor(profile)
        if (schema?.usbOnlyPeq == true) return false
        val rid = resolveReportId(transport, profile)
        val cmd = schema?.findControl("uac_version", "uac_version_nui", "uac", "uac_1_0")?.setCmd ?: CMD_UAC_VERSION
        return usbSend(transport, schema, rid, cmd, byteArrayOf((mode and 0xFF).toByte()))
    }

    override suspend fun setLedMode(transport: DacTransport, profile: DacProfile, mode: Int): Boolean {
        val schema = schemaFor(profile)
        val rid = resolveReportId(transport, profile)
        if (isGigaWitBridge()) {
            return usbSend(transport, schema, rid, GIGA_CMD_SET_BRIGHTNESS, byteArrayOf((mode and 0xFF).toByte()))
        }
        if (schema?.usbOnlyPeq == true) return false
        val cmd = schema?.findControl("led_light", "Indicator_light_switch", "indicator_light_brightness", "led_title", "indicaot")?.setCmd ?: CMD_LED_LIGHT
        return usbSend(transport, schema, rid, cmd, byteArrayOf((mode and 0xFF).toByte()))
    }

    override suspend fun setSurround3d(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean {
        val schema = schemaFor(profile)
        val rid = resolveReportId(transport, profile)
        return usbSend(transport, schema, rid, CMD_GAIN_3D, byteArrayOf(if (enabled) 1 else 0))
    }

    override suspend fun setExtraControl(
        transport: DacTransport,
        profile: DacProfile,
        key: String,
        value: DacControlValue
    ): Boolean {
        val schema = schemaFor(profile) ?: return false
        if (schema.usbOnlyPeq) return false
        val ctrl = schema.controls.firstOrNull { it.key == key } ?: return false
        if (ctrl.setCmd <= 0) return false
        val rid = resolveReportId(transport, profile)
        val effectiveValue = if (ctrl.allowMaxValueChange && value.dynamicMax == null) {
            value.copy(dynamicMax = lastKnownStepMax.coerceAtLeast(ctrl.max ?: 60))
        } else {
            value
        }
        if (key == "vol_control") {
            lastKnownStepMax = FiioSchemaControlsHelper.resolveStepMax(schema, effectiveValue.intValue, lastKnownStepMax)
        }
        val payload = FiioSchemaControlsHelper.encodePayload(ctrl, effectiveValue, presetSlotId = lastKnownPresetId ?: 0, isUsb = true)
        val ok = usbSend(transport, schema, rid, ctrl.setCmd, payload)
        if (ok && schema.commandBuilderType == 61449 && ctrl.setCmd == 13) {
            lastKa5VolCtrl = payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        }
        return ok
    }

    override suspend fun setDeviceName(transport: DacTransport, profile: DacProfile, name: String): Boolean {
        val schema = schemaFor(profile)
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val rid = resolveReportId(transport, profile)
        if (isGigaWitBridge()) {
            val bytes = clean.encodeToByteArray().let { if (it.size > 32) it.copyOf(32) else it }
            return usbSend(transport, schema, rid, GIGA_CMD_SET_NAME, bytes)
        }
        if (schema == null || schema.usbOnlyPeq || schema.editNameSetCmd <= 0) return false
        val chunks = FiioSchemaControlsHelper.encodeDeviceNameChunks(clean, schema.editNameMaxBytes, schema.editNameRuler)
        if (chunks.isEmpty()) return false
        for (chunk in chunks) {
            if (!usbSend(transport, schema, rid, schema.editNameSetCmd, chunk)) return false
            delay(25)
        }
        return true
    }

    override suspend fun sendDeviceAction(transport: DacTransport, profile: DacProfile, cmdId: Int): Boolean {
        val schema = schemaFor(profile)
        if (cmdId <= 0) return false
        val rid = resolveReportId(transport, profile)
        if (isGigaWitBridge()) return usbSend(transport, schema, rid, cmdId)
        if (schema == null || schema.usbOnlyPeq) return false
        return usbSend(transport, schema, rid, cmdId)
    }

    override suspend fun refreshLiveTelemetry(
        transport: DacTransport,
        profile: DacProfile,
        currentState: DacDeviceState,
        includeAllControls: Boolean
    ): DacDeviceState? {
        if (isGigaWitBridge()) {
            if (!includeAllControls) return null
            val schema = schemaFor(profile)
            val rid = resolveReportId(transport, profile)
            var next = currentState
            usbRequest(transport, schema, rid, GIGA_CMD_GET_BRIGHTNESS)?.firstOrNull()?.let { b ->
                next = next.copy(ledMode = b.toInt() and 0xFF)
            }
            usbRequest(transport, schema, rid, GIGA_CMD_GET_NAME)?.let { raw ->
                val nm = raw.takeWhile { it != 0.toByte() }.toByteArray().decodeToString().trim()
                if (nm.isNotEmpty()) next = next.copy(deviceName = nm)
            }
            return next
        }
        val schema = schemaFor(profile)
        val peqSchema = schema?.let(::effectiveUsbPeqSchema)
        val caps = buildCapabilities(profile)
        val rid = resolveReportId(transport, profile)
        val cbt = schema?.commandBuilderType ?: 101
        val cmdDelay = (schema?.deviceCommandInterval ?: 0).toLong().coerceIn(0L, 100L)
        var updated = currentState
        if (schema == null || !schema.usbOnlyPeq) {
            val volCtrl = schema?.findControl("vol_output", "device_volume", "device_volume_nui")
            val volCmd = volCtrl?.getCmd ?: CMD_VOL_OUTPUT
            usbRequest(transport, schema, rid, volCmd)?.let {
                val vol = if (volCtrl != null) FiioSchemaControlsHelper.decodeResponse(volCtrl, it, isUsb = true).intValue
                else it.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                updated = updated.copy(volume = vol.coerceIn(0, updated.maxVolume))
            }
        }
        val supportsPeq = (peqSchema?.eqPresetSupport != false || peqSchema.eqBandSupport) &&
            cbt !in setOf(61448, 61449, 61450, 61452, 61454)
        var presetChanged = false
        if (supportsPeq) {
            val preCmd = peqSchema?.eqPresetGetCmd ?: if (variant == ProtocolFamily.FIIO_V2) CMD_PEQ_PRE_V2 else CMD_PEQ_PRE
            usbRequest(transport, peqSchema, rid, preCmd)?.let { p ->
                val pid = when {
                    p.size >= 2 -> BytePack.getU16Be(p, 0)
                    p.isNotEmpty() -> p[0].toInt() and 0xFF
                    else -> updated.activePresetId
                }
                if (pid != updated.activePresetId) presetChanged = true
                lastKnownPresetId = pid
                if (peqSchema?.bypassPresetId == null || pid != peqSchema.bypassPresetId) lastNonBypassPresetId = pid
                updated = updated.copy(activePresetId = pid)
                if (peqSchema?.eqSwitchSupport != true && peqSchema?.bypassPresetId != null) {
                    updated = updated.copy(eqEnabled = pid != peqSchema.bypassPresetId)
                }
            }
            if (peqSchema?.eqSwitchSupport == true && peqSchema.eqSwitchGetCmd > 0) {
                usbRequest(transport, peqSchema, rid, peqSchema.eqSwitchGetCmd)?.let { p ->
                    updated = updated.copy(eqEnabled = FiioPeqCodec.decodeEqSwitch(p, peqSchema.eqSwitchRuler))
                }
            }
            if ((includeAllControls || presetChanged) && peqSchema != null && caps.hasGlobalGain) {
                val mgPayload = FiioPeqCodec.buildQueryMasterGainPayload(peqSchema, updated.activePresetId)
                usbRequest(transport, peqSchema, rid, peqSchema.masterGainGetCmd, mgPayload)?.let { p ->
                    FiioPeqCodec.decodeMasterGain(p, peqSchema.parseRulerType)?.let { g ->
                        updated = updated.copy(globalGainDb = g.coerceIn(caps.minGainDb, caps.maxGainDb))
                    }
                }
            }
            if (presetChanged) {
                updated = updated.copy(
                    bands = readPeqBandsHid(transport, peqSchema, rid, updated.activePresetId, caps, cmdDelay)
                )
            }
        }
        if (includeAllControls && schema != null && !schema.usbOnlyPeq) {
            schema.findControl("filter", "utws17_filter")?.let { fCtrl ->
                if (fCtrl.getCmd > 0) {
                    usbRequest(transport, schema, rid, fCtrl.getCmd)?.let { p ->
                        updated = updated.copy(
                            dacFilterIndex = FiioSchemaControlsHelper.decodeResponse(fCtrl, p, isUsb = true).intValue
                        )
                    }
                }
            }
            schema.findControl("gain", "gain_level")?.let { gCtrl ->
                if (gCtrl.getCmd > 0) {
                    usbRequest(transport, schema, rid, gCtrl.getCmd)?.let { p ->
                        updated = updated.copy(
                            workModeIndex = FiioSchemaControlsHelper.decodeResponse(gCtrl, p, isUsb = true).intValue
                        )
                    }
                }
            }
            schema.findControl("channel_balance")?.let { balCtrl ->
                val maxBal = balCtrl.max ?: 12
                if (balCtrl.getCmd > 0) {
                    usbRequest(transport, schema, rid, balCtrl.getCmd)?.let { p ->
                        updated = updated.copy(
                            channelBalance = FiioSchemaControlsHelper.decodeChannelBalance(p, balCtrl.parseRulerType, isUsb = true)
                                .coerceIn(-maxBal, maxBal)
                        )
                    }
                }
            }
            val refreshedExtras = readExtraControlsHid(transport, schema, rid)
            if (refreshedExtras.isNotEmpty()) {
                val (mergedExtras, effMax) = FiioSchemaControlsHelper.recomputeVolumeConstraints(
                    schema,
                    updated.extraControlValues + refreshedExtras,
                    lastKnownStepMax
                )
                lastKnownStepMax = FiioSchemaControlsHelper.resolveStepMax(
                    schema,
                    mergedExtras["vol_control"]?.intValue,
                    lastKnownStepMax
                )
                updated = if (schema.findControl("vol_control", "vol_limit") != null) {
                    updated.copy(
                        maxVolume = effMax,
                        volume = updated.volume.coerceIn(0, effMax),
                        extraControlValues = mergedExtras
                    )
                } else {
                    updated.copy(extraControlValues = mergedExtras)
                }
            }
        }
        return updated
    }

    private suspend fun usbRequest(
        transport: DacTransport,
        schema: FiioDeviceSchema?,
        reportId: Int,
        cmd: Int,
        data: ByteArray = byteArrayOf()
    ): ByteArray? {
        val cbt = schema?.commandBuilderType ?: 101
        val acIdx = transport.descriptor.acInterfaceIndex
        val hidIdx = transport.descriptor.hidInterfaceIndex
        return when {
            variant == ProtocolFamily.FIIO_GIGAWIT -> {
                val featureId = (cmd ushr 8) and 0xFF
                val gigaCmd = cmd and 0xFF
                val frame = FiioUsbCodecs.gigaWitBuildPacket(featureId, gigaCmd, data)
                val hid = FiioUsbCodecs.buildJiedianUsbReport(frame)
                if (!transport.send(hid, 500)) return null
                repeat(4) {
                    val rx = transport.receive(64, 350) ?: return null
                    val extracted = FiioUsbCodecs.gigaWitExtractPayload(rx, featureId, gigaCmd)
                    if (extracted != null) return extracted
                }
                null
            }
            cbt in setOf(61449, 61452, 61454) -> {
                val spec = FiioUsbCodecs.specsForCbt(cbt)[cmd] ?: return null
                val a7 = FiioUsbCodecs.transceiveC7A5Aggregate(transport, spec.aggReg, acInterfaceIndex = acIdx) ?: return null
                FiioUsbCodecs.extractC7A5Read(cbt, cmd, a7, lastKa5VolCtrl)
            }
            cbt == 61450 -> {
                val reg = FiioUsbCodecs.ka1ReadRegOf(cmd) ?: return null
                val buf = ByteArray(64)
                val read = transport.controlTransfer(0xA1, 0x01, 0x0100 or reg, acIdx, buf, 500)
                if (read <= 0) return null
                val regVal = buf[0].toInt() and 0xFF
                if (reg == 0xEA) lastKa1RegEa = regVal
                FiioUsbCodecs.ka1Extract(cmd, regVal)
            }
            cbt == 61448 -> {
                if (cmd == 4) {
                    FiioUsbCodecs.ka13ReadUacLeft(transport)
                }
                val seqNow = ka13Seq
                ka13Seq = (ka13Seq + 1) and 0xFF
                val report = FiioUsbCodecs.ka13BuildReadReport(cmd, seqNow) ?: return null
                val out = transport.controlTransfer(0x21, 0x09, 0x0200, hidIdx, report, 500)
                if (out < 0) return null
                val rx = transport.receive(64, 400) ?: return null
                FiioUsbCodecs.ka13DecodeRead(cmd, rx)
            }
            else -> requestCommand(transport, reportId, cmd, data)?.let { extractPayload(it) }
        }
    }

    private suspend fun usbSend(
        transport: DacTransport,
        schema: FiioDeviceSchema?,
        reportId: Int,
        cmd: Int,
        data: ByteArray = byteArrayOf()
    ): Boolean {
        val cbt = schema?.commandBuilderType ?: 101
        val acIdx = transport.descriptor.acInterfaceIndex
        val hidIdx = transport.descriptor.hidInterfaceIndex
        return when {
            variant == ProtocolFamily.FIIO_GIGAWIT -> {
                val featureId = (cmd ushr 8) and 0xFF
                val gigaCmd = cmd and 0xFF
                val frame = FiioUsbCodecs.gigaWitBuildPacket(featureId, gigaCmd, data)
                transport.send(FiioUsbCodecs.buildJiedianUsbReport(frame))
            }
            cbt in setOf(61449, 61452, 61454) -> {
                FiioUsbCodecs.writeC7A5Control(transport, cbt, cmd, data, lastKa5VolCtrl, acInterfaceIndex = acIdx)
            }
            cbt == 61450 -> {
                val reg = FiioUsbCodecs.ka1ReadRegOf(cmd) ?: return false
                val valByte = data.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                val wireVal = if (FiioUsbCodecs.ka1IsReg234Field(cmd)) {
                    FiioUsbCodecs.ka1ApplyReg234Field(cmd, lastKa1RegEa, valByte).also { lastKa1RegEa = it }
                } else valByte
                val buf = ByteArray(64).also {
                    it[0] = (reg and 0xFF).toByte()
                    it[1] = (wireVal and 0xFF).toByte()
                }
                transport.controlTransfer(0x21, 0x09, 0x0200, acIdx, buf, 500) >= 0
            }
            cbt == 61448 -> {
                val buf = ByteArray(64).also {
                    it[0] = (cmd and 0xFF).toByte()
                    data.copyInto(it, destinationOffset = 1)
                }
                transport.controlTransfer(0x21, 0x09, 0x0200, hidIdx, buf, 500) >= 0
            }
            else -> {
                val frame = wrapHidReport(transport, reportId, buildSetFrame(cmd, data))
                transport.send(frame)
            }
        }
    }

    internal fun buildSetFrame(cmd: Int, data: ByteArray = byteArrayOf()): ByteArray =
        buildFrame(SET_HEAD, SET_START, cmd, data)

    internal fun buildGetFrame(cmd: Int, data: ByteArray = byteArrayOf()): ByteArray =
        buildFrame(GET_HEAD, GET_START, cmd, data)

    private fun buildFrame(head: Int, start: Int, cmd: Int, data: ByteArray): ByteArray {
        val currentSeq = seq
        seq = if (seq >= 0xFFFF) 0 else seq + 1

        val bodyLen = 6 + data.size
        val frame = ByteArray(bodyLen + 2)
        frame[0] = head.toByte()
        frame[1] = start.toByte()
        frame[2] = ((currentSeq ushr 8) and 0xFF).toByte()
        frame[3] = (currentSeq and 0xFF).toByte()
        frame[4] = (cmd and 0xFF).toByte()
        frame[5] = (data.size and 0xFF).toByte()
        data.copyInto(frame, destinationOffset = 6)
        frame[bodyLen] = CrcUtils.crc8Maxim(frame, 0, bodyLen).toByte()
        frame[bodyLen + 1] = TAIL.toByte()
        return frame
    }

    internal fun wrapHidReport(transport: DacTransport, reportId: Int, frame: ByteArray): ByteArray =
        wrapHidReport(reportId, frame, transport.descriptor.hidOutputReportSizes[reportId] ?: transport.descriptor.hidReportSize)

    internal fun wrapHidReport(reportId: Int, frame: ByteArray, reportSize: Int): ByteArray {
        val minSize = if (reportId > 0) frame.size + 1 else frame.size
        val totalSize = reportSize.coerceAtLeast(minSize)
        val out = ByteArray(totalSize)
        if (reportId > 0) {
            out[0] = (reportId and 0xFF).toByte()
            frame.copyInto(out, destinationOffset = 1)
        } else {
            frame.copyInto(out, destinationOffset = 0)
        }
        return out
    }

    private suspend fun requestCommand(
        transport: DacTransport,
        reportId: Int,
        cmd: Int,
        data: ByteArray = byteArrayOf()
    ): ByteArray? {
        val req = wrapHidReport(transport, reportId, buildGetFrame(cmd, data))
        if (!transport.send(req, timeoutMs = 450)) return null
        repeat(6) {
            val resp = transport.receive(expectedSize = transport.descriptor.hidReportSize.coerceAtLeast(64), timeoutMs = 350)
                ?: return null
            val startIdx = findResponseFrameStart(resp, cmd)
            if (startIdx >= 0) {
                return resp.copyOfRange(startIdx, resp.size)
            }
        }
        return null
    }

    private fun findResponseFrameStart(raw: ByteArray, expectedCmd: Int): Int {
        for (i in 0 until raw.size - 6) {
            val b0 = raw[i].toInt() and 0xFF
            val b1 = raw[i + 1].toInt() and 0xFF
            if (b0 == GET_HEAD && b1 == GET_START && (raw[i + 4].toInt() and 0xFF) == expectedCmd) {
                return i
            }
        }
        return -1
    }

    private fun extractPayload(frame: ByteArray): ByteArray? {
        if (frame.size < 8) return null
        val len = frame[5].toInt() and 0xFF
        if (6 + len > frame.size) return null
        return frame.copyOfRange(6, 6 + len)
    }

    private fun parseFirmwareVersion(payload: ByteArray): String? {
        val ascii = payload.takeWhile { it in 0x20..0x7E }.toByteArray().decodeToString().trim()
        if (ascii.length >= 2 && ascii.any { it.isDigit() }) return ascii
        if (payload.size in 2..4) {
            return payload.joinToString(".") { (it.toInt() and 0xFF).toString() }
        }
        return ascii.ifEmpty { null }
    }

    private fun resolveReportId(transport: DacTransport, profile: DacProfile): Int {
        val outSizes = transport.descriptor.hidOutputReportSizes
        val preferred = resolveDefaultReportId(profile)
        if (outSizes.isEmpty()) return preferred
        if (outSizes.containsKey(preferred)) return preferred
        return outSizes.keys.firstOrNull { it > 0 } ?: outSizes.keys.first()
    }

    private fun resolveDefaultReportId(profile: DacProfile): Int {
        val name = profile.model.uppercase()
        return when {
            name.contains("KA17") -> 1
            name.contains("JA11") -> 2
            name.contains("USB20") -> 0
            profile.reportId > 0 -> profile.reportId
            else -> 7
        }
    }

    companion object {
        const val SET_HEAD = 0xAA
        const val SET_START = 0x0A
        const val GET_HEAD = 0xBB
        const val GET_START = 0x0B
        const val TAIL = 0xEE

        const val CMD_VOL_MAX = 1
        const val CMD_VOL_OUTPUT = 2
        const val CMD_FILTER_MODE = 4
        const val CMD_LED_LIGHT = 6
        const val CMD_VOL_BALANCE = 7
        const val CMD_FIRMWARE_VERSION = 11
        const val CMD_MIC_SWITCH = 18
        const val CMD_UAC_VERSION = 32
        const val CMD_PEQ_PARAMS = 0x15
        const val CMD_PEQ_PRE = 0x16
        const val CMD_GLOBAL_GAIN = 0x17
        const val CMD_PEQ_COUNT = 0x18
        const val CMD_PEQ_SAVE = 0x19
        const val CMD_PEQ_SWITCH = 0x1A
        const val CMD_RESET_PRE = 0x1B
        const val CMD_PEQ_NAME = 0x30
        const val CMD_MIC_MONITOR_SWITCH = 29
        const val CMD_MIC_VOL = 34
        const val CMD_GAIN_3D = 53
        const val CMD_PEQ_PRE_V2 = 0x60

        // GigaWit USB Bridge encoded (featureId << 8) | gigaCmd
        const val GIGA_CMD_FW = 0x0005
        const val GIGA_CMD_GET_NAME = 0x1800
        const val GIGA_CMD_SET_NAME = 0x1801
        const val GIGA_CMD_CLEAR_PAIRED = 0x1814
        const val GIGA_CMD_GET_BRIGHTNESS = 0x1852
        const val GIGA_CMD_SET_BRIGHTNESS = 0x1853
        const val GIGA_CMD_FACTORY_RESET = 0x1879
        const val GIGA_CMD_REBOOT = 0x187A

        private val DEFAULT_PRESETS = listOf(
            NamedOption(0, "Jazz"),
            NamedOption(1, "Pop"),
            NamedOption(2, "Rock"),
            NamedOption(3, "Dance"),
            NamedOption(4, "R&B"),
            NamedOption(5, "Classic"),
            NamedOption(6, "Hip-Hop"),
            NamedOption(160, "User 1"),
            NamedOption(161, "User 2"),
            NamedOption(162, "User 3")
        )

        private val DEFAULT_DAC_FILTERS = listOf(
            NamedOption(0, "Fast Roll-off Linear"),
            NamedOption(1, "Slow Roll-off Linear"),
            NamedOption(2, "Fast Roll-off Minimum"),
            NamedOption(3, "Slow Roll-off Minimum"),
            NamedOption(4, "Apodizing")
        )

        private val DEFAULT_LED_OPTIONS = listOf(
            NamedOption(0, "Off"),
            NamedOption(1, "On"),
            NamedOption(2, "Dim")
        )
    }
}
