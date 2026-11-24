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
import com.suprcharg3d.dongleCTRL.protocol.TransportType
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Universal FiiO Bluetooth protocol driver covering:
 * - [ProtocolFamily.FIIO_BT_GAIA]: GAIA V3 (BTR17, UTWS17, BTR25, BR15 R2R, UTWS5, FW3/5)
 *   and GAIA V1 (BTR15, BTR13, BTR7, BTR5, BTR3, RETRO NANO, K7, K19, Q15, K9, Q7, SP5)
 * - [ProtocolFamily.FIIO_BT_UNIVERSAL]: Universal BT 0xF1 0x10 (K13 R2R, SA1, EH11, EH13, LEVEL 1)
 * - [ProtocolFamily.FIIO_BT_BR13]: JieLi RCSP 0xFE 0xDC 0xBA ... 0xEF (BR13)
 */
class FiioBtProtocol(
    override val family: ProtocolFamily = ProtocolFamily.FIIO_BT_GAIA,
    private val schemas: FiioSchemaCatalog? = null
) : DacProtocol {

    override val displayName: String = family.displayName

    @Volatile
    private var lastKnownPresetId: Int? = null
    @Volatile
    private var lastNonBypassPresetId: Int? = null
    @Volatile
    private var lastKnownStepMax: Int = 60

    private fun schemaFor(profile: DacProfile): FiioDeviceSchema? =
        schemas?.let { cat ->
            profile.fiioDeviceType?.let { cat.findByDeviceType(it) }
                ?: cat.matchName(profile.model)
                ?: cat.matchName(profile.productNameMatch)
        }

    override fun buildCapabilities(profile: DacProfile): DacCapabilities {
        val schema = schemaFor(profile)
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
                globalGainRange = -12.0..12.0,
                hasVolume = true,
                maxVolume = 60,
                hasChannelBalance = true,
                hasBattery = true,
                supportsRamRefresh = false,
                supportsFlashSave = false
            )
        }

        val cbt = schema.commandBuilderType ?: 5
        if (cbt == 0) {
            return DacCapabilities(
                maxBands = 0,
                minGainDb = schema.gainMin,
                maxGainDb = schema.gainMax,
                minQ = 0.25,
                maxQ = 8.0,
                supportedFilters = emptyList(),
                sampleRate = 48_000,
                hasEqSwitch = false,
                hasGlobalGain = false,
                hasBattery = false,
                presets = emptyList(),
                supportsRamRefresh = false,
                supportsFlashSave = false
            )
        }

        val volCtrl = schema.findControl("device_volume", "vol_output", "vol_of_bluetooth", "device_volume_nui")
        val balCtrl = schema.findControl("channel_balance")
        val filterCtrl = schema.findControl("filter", "utws17_filter")
        val gainCtrl = schema.findControl("gain_level", "gain")
        val ampCtrl = schema.findControl("dac_parallel", "boost_bal_out_mode", "output_estimation", "dac_work_mode")
        val uacCtrl = schema.findControl("uac", "uac_version", "uac_version_nui")
        val ledCtrl = schema.findControl("led_title", "Indicator_light_switch", "indicator_light_brightness", "rgb_switch")
        val presetOptions = schema.presets.map { NamedOption(it.id, it.label) }
        val supportsPeqBands = schema.eqBandSupport && schema.bandCount > 0
        val hasDirectSwitch = schema.eqSwitchSupport && schema.eqSwitchSetCmd > 0
        val effectiveEqSwitch = hasDirectSwitch ||
            (schema.bypassPresetId != null && (supportsPeqBands || presetOptions.isNotEmpty()))

        return DacCapabilities(
            maxBands = if (supportsPeqBands) schema.bandCount.coerceIn(5, 31) else 0,
            minGainDb = schema.gainMin,
            maxGainDb = schema.gainMax,
            minQ = 0.25,
            maxQ = 8.0,
            supportedFilters = if (supportsPeqBands) schema.supportedFilterTypes() else emptyList(),
            sampleRate = 48_000,
            hasEqSwitch = effectiveEqSwitch,
            eqToggleUsesBypass = !hasDirectSwitch && schema.bypassPresetId != null,
            hasGlobalGain = schema.masterGainSupport,
            globalGainRange = schema.gainMin..schema.gainMax,
            hasDacFilter = filterCtrl != null && filterCtrl.options.isNotEmpty(),
            dacFilterOptions = filterCtrl?.options.orEmpty(),
            hasOutputMode = ampCtrl != null && ampCtrl.options.isNotEmpty(),
            outputModeOptions = ampCtrl?.options.orEmpty(),
            hasWorkMode = gainCtrl != null && gainCtrl.options.isNotEmpty(),
            workModeOptions = gainCtrl?.options.orEmpty(),
            hasVolume = volCtrl != null,
            maxVolume = volCtrl?.max ?: 60,
            hasChannelBalance = balCtrl != null,
            channelBalanceMax = balCtrl?.max ?: 12,
            hasUacSwitch = uacCtrl != null && uacCtrl.options.isNotEmpty(),
            uacOptions = uacCtrl?.options.orEmpty(),
            hasLedControl = ledCtrl != null,
            ledOptions = ledCtrl?.options?.takeIf { it.isNotEmpty() } ?: DEFAULT_LED_OPTIONS,
            hasBattery = schema.batteryGetCmd > 0,
            presets = presetOptions,
            presetCategories = schema.presets.mapNotNull { p -> p.category?.let { p.id to it } }.toMap(),
            writablePresetIds = if (supportsPeqBands) schema.customPresetIds else emptySet(),
            bypassPresetId = schema.bypassPresetId,
            canRenamePresets = (supportsPeqBands || presetOptions.isNotEmpty()) &&
                schema.editEqNameSupport && schema.editEqNameSetCmd > 0,
            presetNameMaxChars = 8,
            supportsRamRefresh = false,
            supportsFlashSave = schema.eqSaveSupport,
            extraControls = FiioSchemaControlsHelper.buildExtraControls(schema),
            canRenameDevice = schema.editNameSetCmd > 0,
            deviceNameMaxBytes = schema.editNameMaxBytes.coerceIn(8, 32),
            clearPairingCmd = schema.clearPairingCmd,
            resetDefaultCmd = schema.resetDefaultCmd,
            closeDeviceCmd = schema.closeDeviceCmd,
            btResetDefaultCmd = schema.btResetDefaultCmd,
            systemStandbyCmd = schema.systemStandbyCmd
        )
    }

    override suspend fun readDeviceState(transport: DacTransport, profile: DacProfile): DacDeviceState {
        transport.drainInput()
        val schema = schemaFor(profile) ?: return DacDeviceState(bands = DefaultBands.flatBands(profile.bandCount))
        val caps = buildCapabilities(profile)

        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        if (cbt == 0) {
            return DacDeviceState(bands = emptyList())
        }
        val useShell = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema)
        val batchCache: Map<Int, ByteArray> = if (schema.batchQuerySupported && schema.batchQueryCmd > 0 && cbt in setOf(4, 5, 6)) {
            queryBatchCache(transport, schema, cbt, isBle, useShell)
        } else emptyMap()

        suspend fun fetchCmd(cmd: Int, payload: ByteArray = byteArrayOf(), vendorId: Int = VENDOR_FIIO, allowBatch: Boolean = true): ByteArray? {
            if (cmd <= 0) return null
            if (allowBatch && payload.isEmpty()) batchCache[cmd]?.let { return it }
            return requestCmd(transport, cbt, isBle, cmd, payload, vendorId = vendorId, useShell24 = useShell)
        }

        // Battery
        val batteryPct = if (schema.batteryGetCmd > 0) {
            val isLegacyV1Battery = !useShell && cbt in setOf(1, 61444) && schema.batteryGetCmd == 0x0301
            val batVendor = if (isLegacyV1Battery) VENDOR_QUALCOMM else VENDOR_FIIO
            fetchCmd(schema.batteryGetCmd, vendorId = batVendor, allowBatch = batVendor == VENDOR_FIIO)?.let { payload ->
                val rawB = payload.lastOrNull()?.toInt()?.and(0xFF) ?: -1
                if (cbt == 61451) ((rawB - 1).coerceIn(0, 4)) * 25
                else rawB.takeIf { it in 0..100 }
            }
        } else null

        val codecLabel = if (schema.codecGetCmd > 0) {
            fetchCmd(schema.codecGetCmd)?.let { payload ->
                val raw = payload.lastOrNull()?.toInt()?.and(0xFF)
                schema.codecOptions.firstOrNull { it.id == raw }?.label
            }
        } else null

        val activePreset = if (schema.eqPresetSupport && schema.eqPresetGetCmd > 0) {
            fetchCmd(schema.eqPresetGetCmd)?.let { p ->
                parseUintPayload(p)
            } ?: schema.firstCustomPresetId
        } else schema.firstCustomPresetId

        lastKnownPresetId = activePreset
        if (schema.bypassPresetId == null || activePreset != schema.bypassPresetId) {
            lastNonBypassPresetId = activePreset
        }

        val eqEnabled = if (schema.eqSwitchSupport && schema.eqSwitchGetCmd > 0) {
            fetchCmd(schema.eqSwitchGetCmd)?.let { p ->
                FiioPeqCodec.decodeEqSwitch(p, schema.eqSwitchRuler)
            } ?: true
        } else if (schema.bypassPresetId != null) {
            activePreset != schema.bypassPresetId
        } else true

        val globalGain = if (schema.masterGainSupport && schema.masterGainGetCmd > 0) {
            val mgPayload = FiioPeqCodec.buildQueryMasterGainPayload(schema, activePreset)
            fetchCmd(schema.masterGainGetCmd, mgPayload, allowBatch = mgPayload.isEmpty())?.let { p ->
                FiioPeqCodec.decodeMasterGain(p, schema.parseRulerType)?.coerceIn(caps.minGainDb, caps.maxGainDb)
            } ?: 0.0
        } else 0.0

        val volCtrl = schema.findControl("device_volume", "vol_output", "vol_of_bluetooth", "device_volume_nui")
        val maxVolCtrl = schema.findControl("vol_control", "vol_limit")
        val stepVal = if (maxVolCtrl != null && maxVolCtrl.key == "vol_control" && maxVolCtrl.getCmd > 0) {
            fetchCmd(maxVolCtrl.getCmd)?.let { FiioSchemaControlsHelper.decodeResponse(maxVolCtrl, it, isUsb = false).intValue }
        } else null
        val volMax = FiioSchemaControlsHelper.resolveStepMax(schema, stepVal, volCtrl?.max ?: 60)
        lastKnownStepMax = volMax

        val vol = if (volCtrl != null && volCtrl.getCmd > 0) {
            fetchCmd(volCtrl.getCmd)?.let { p ->
                FiioSchemaControlsHelper.decodeResponse(volCtrl, p, isUsb = false).intValue.coerceIn(0, volMax)
            } ?: 0
        } else 0

        val filterCtrl = schema.findControl("filter", "utws17_filter")
        val filterIdx = if (filterCtrl != null && filterCtrl.getCmd > 0) {
            fetchCmd(filterCtrl.getCmd)?.let { p ->
                FiioSchemaControlsHelper.decodeResponse(filterCtrl, p, isUsb = false).intValue
            } ?: 0
        } else 0

        val gainCtrl = schema.findControl("gain_level", "gain")
        val gainIdx = if (gainCtrl != null && gainCtrl.getCmd > 0) {
            fetchCmd(gainCtrl.getCmd)?.let { p ->
                FiioSchemaControlsHelper.decodeResponse(gainCtrl, p, isUsb = false).intValue
            } ?: 0
        } else 0

        val ampCtrl = schema.findControl("dac_parallel", "boost_bal_out_mode", "output_estimation", "dac_work_mode")
        val ampIdx = if (ampCtrl != null && ampCtrl.getCmd > 0) {
            fetchCmd(ampCtrl.getCmd)?.let { p ->
                FiioSchemaControlsHelper.decodeResponse(ampCtrl, p, isUsb = false).intValue
            } ?: 0
        } else 0

        val balCtrl = schema.findControl("channel_balance")
        val balance = if (balCtrl != null && balCtrl.getCmd > 0) {
            fetchCmd(balCtrl.getCmd)?.let { p ->
                decodeBtBalance(cbt, p, balCtrl.parseRulerType)
            } ?: 0
        } else 0

        val uacCtrl = schema.findControl("uac", "uac_version", "uac_version_nui")
        val uacMode = if (uacCtrl != null && uacCtrl.getCmd > 0) {
            fetchCmd(uacCtrl.getCmd)?.let { p ->
                FiioSchemaControlsHelper.decodeResponse(uacCtrl, p, isUsb = false).intValue
            } ?: 1
        } else 1

        val (extraValues, activeVolLimit) = readExtraControlsMap(transport, schema, cbt, isBle, useShell, volMax, batchCache)

        val devName = if (schema.editNameGetCmd > 0) {
            val frames = if (schema.editNameRuler == 1) {
                requestCmdMulti(transport, cbt, isBle, schema.editNameGetCmd, maxFrames = 6, useShell24 = useShell)
            } else {
                listOfNotNull(fetchCmd(schema.editNameGetCmd))
            }
            FiioSchemaControlsHelper.decodeDeviceName(frames, schema.editNameRuler)
        } else null

        val bands = readPeqBands(transport, schema, cbt, isBle, caps, useShell, activePreset)
        val customNames = readPresetNamesBt(transport, schema, cbt, isBle, caps, useShell)

        return DacDeviceState(
            eqEnabled = eqEnabled,
            activePresetId = activePreset,
            globalGainDb = globalGain,
            bands = bands,
            customPresetNames = customNames,
            dacFilterIndex = filterIdx,
            outputModeIndex = ampIdx,
            workModeIndex = gainIdx,
            volume = vol,
            maxVolume = (activeVolLimit?.takeIf { it in 20..volMax } ?: volMax),
            channelBalance = balance.coerceIn(-caps.channelBalanceMax, caps.channelBalanceMax),
            uacMode = uacMode,
            batteryPercent = batteryPct,
            bluetoothCodec = codecLabel,
            deviceName = devName,
            extraControlValues = extraValues
        )
    }

    private suspend fun readBatchPresetNamesBt(
        transport: DacTransport,
        schema: FiioDeviceSchema,
        cbt: Int,
        isBle: Boolean,
        writablePresetIds: Set<Int>,
        useShell24: Boolean
    ): Map<Int, String>? {
        val getCmd = schema.editEqNameGetCmd
        if (!schema.editEqNameSupport || !schema.editEqNameSupportBatch || getCmd <= 0) return null
        val ranges = FiioPeqCodec.encodeQueryBatchEqNamesPayloads(schema, writablePresetIds)
        if (ranges.isEmpty()) return emptyMap()
        val interval = schema.deviceCommandInterval.toLong().coerceAtLeast(0L)
        val targetCmd16 = if (getCmd > 0xFFFF) (getCmd ushr 8) and 0xFFFF else (getCmd and 0xFFFF)
        val writableList = writablePresetIds.toList()
        val out = LinkedHashMap<Int, String>()
        var anyResponded = false
        for ((idx, rangeReq) in ranges.withIndex()) {
            if (interval > 0L && idx > 0) delay(interval)
            val pkt = buildPacket(cbt, isBle, getCmd, rangeReq, useShell24 = useShell24)
            if (!transport.send(pkt, timeoutMs = 800)) continue
            val expectedCount = ((rangeReq[1].toInt() and 0xFF) - (rangeReq[0].toInt() and 0xFF) + 1).coerceAtLeast(1)
            val coveredSlots = HashSet<Int>()
            var attempts = 0
            while (coveredSlots.size < expectedCount && attempts < expectedCount + 6) {
                attempts++
                val timeout = if (coveredSlots.isEmpty()) 550 else 180
                val raw = transport.receive(expectedSize = 512, timeoutMs = timeout) ?: break
                if (useShell24 && isV3ErrorFrame(raw, isBle, getCmd)) break
                val extracted = extractResponsePayload(raw, isBle, targetCmd16, getCmd, cbt, useShell24 = useShell24)
                    ?: continue
                if (FiioPeqCodec.accumulateBatchEqNamesFrame(extracted, writableList, schema.parseRulerType, out, coveredSlots)) {
                    anyResponded = true
                }
            }
        }
        return if (anyResponded) out else null
    }

    private suspend fun readPresetNamesBt(
        transport: DacTransport,
        schema: FiioDeviceSchema,
        cbt: Int,
        isBle: Boolean,
        caps: DacCapabilities,
        useShell24: Boolean
    ): Map<Int, String> {
        if (!caps.canRenamePresets || schema.editEqNameGetCmd <= 0) return emptyMap()
        val getCmd = schema.editEqNameGetCmd
        if (schema.editEqNameSupportBatch) {
            return readBatchPresetNamesBt(transport, schema, cbt, isBle, caps.writablePresetIds, useShell24).orEmpty()
        }
        val interval = schema.deviceCommandInterval.toLong().coerceAtLeast(0L)
        val out = LinkedHashMap<Int, String>()
        for ((idx, slotId) in caps.writablePresetIds.withIndex()) {
            if (interval > 0L && idx > 0) delay(interval)
            val req = FiioPeqCodec.encodeQueryEditEqNamePayload(schema, slotId)
            val raw = requestCmd(transport, cbt, isBle, getCmd, req, useShell24 = useShell24) ?: continue
            FiioPeqCodec.decodeEditEqNamePayload(schema, slotId, raw)?.let { (id, text) ->
                if (text.isNotEmpty()) out[id] = text
            }
        }
        return out
    }

    private suspend fun readExtraControlsMap(
        transport: DacTransport,
        schema: FiioDeviceSchema,
        cbt: Int,
        isBle: Boolean,
        useShell: Boolean,
        volMax: Int,
        batchCache: Map<Int, ByteArray> = emptyMap()
    ): Pair<Map<String, DacControlValue>, Int?> {
        val extraValues = LinkedHashMap<String, DacControlValue>()

        for (ctrl in schema.controls) {
            if (ctrl.key.isBlank() || FiioSchemaControlsHelper.isPrimaryControl(ctrl.key) || ctrl.getCmd <= 0) continue
            val raw = batchCache[ctrl.getCmd]
                ?: requestCmd(transport, cbt, isBle, ctrl.getCmd, useShell24 = useShell)
                ?: continue
            var decoded = FiioSchemaControlsHelper.decodeResponse(ctrl, raw, isUsb = false)
            if (ctrl.supportCmd > 0) {
                val supRaw = batchCache[ctrl.supportCmd]
                    ?: requestCmd(transport, cbt, isBle, ctrl.supportCmd, useShell24 = useShell)
                if (supRaw != null) {
                    decoded = decoded.copy(supportMask = FiioSchemaControlsHelper.parseUint(supRaw))
                }
            }
            if (ctrl.queryCmd > 0) {
                val qRaw = batchCache[ctrl.queryCmd]
                    ?: requestCmd(transport, cbt, isBle, ctrl.queryCmd, useShell24 = useShell)
                if (qRaw != null) {
                    decoded = decoded.copy(supportMask = FiioSchemaControlsHelper.parseUint(qRaw))
                }
            }
            extraValues[ctrl.key] = decoded
        }
        val (recomputed, effMax) = FiioSchemaControlsHelper.recomputeVolumeConstraints(schema, extraValues, volMax)
        val activeVolLimit = recomputed["vol_limit"]?.intValue?.takeIf { it in 1..effMax }
        return recomputed to activeVolLimit
    }

    private suspend fun readPeqBands(
        transport: DacTransport,
        schema: FiioDeviceSchema,
        cbt: Int,
        isBle: Boolean,
        caps: DacCapabilities,
        useShell24: Boolean,
        activePreset: Int
    ): List<EqBand> {
        if (caps.maxBands <= 0 || !schema.eqBandSupport || schema.eqBandGetCmd <= 0) return emptyList()
        val count = caps.maxBands.coerceIn(1, 31)
        val effSchema = schema.copy(bandCount = count)
        val flat = FiioPeqCodec.flatBandsForSchema(effSchema)

        if (schema.eqBandSupportBatch || schema.eqBandBatchGetCmd > 0 && schema.eqBandBatchGetCmd != schema.eqBandGetCmd) {
            val batchCmd = schema.eqBandBatchGetCmd.takeIf { it > 0 } ?: schema.eqBandGetCmd
            val qPayload = FiioPeqCodec.buildQueryBatchBandsPayload(effSchema, activePreset)
            val frames = requestCmdMulti(
                transport = transport,
                cbt = cbt,
                isBle = isBle,
                cmd = batchCmd,
                payload = qPayload,
                maxFrames = count,
                useShell24 = useShell24
            )
            if (frames.isNotEmpty()) {
                val parsed = frames.flatMap { FiioPeqCodec.decodeBatchBands(it, effSchema).orEmpty() }
                    .distinctBy { it.index }
                if (parsed.size >= (count / 2).coerceAtLeast(1)) {
                    return flat.mapIndexed { i, def -> parsed.firstOrNull { it.index == i } ?: def }
                }
            }
        }

        val interval = schema.deviceCommandInterval.toLong().coerceAtLeast(0L)
        return flat.mapIndexed { idx, defaultBand ->
            if (interval > 0L && idx > 0) delay(interval)
            val qPayload = FiioPeqCodec.buildQuerySingleBandPayload(effSchema, idx, activePreset)
            val p = requestCmd(transport, cbt, isBle, schema.eqBandGetCmd, qPayload, useShell24 = useShell24)
            p?.let { FiioPeqCodec.decodeSingleBand(it, effSchema, idx) } ?: defaultBand
        }
    }

    override suspend fun writeEqBand(
        transport: DacTransport,
        profile: DacProfile,
        band: EqBand,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        val schema = schemaFor(profile) ?: return false
        if (!schema.eqBandSupport || schema.eqBandSetCmd <= 0) return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val useShell = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema)
        val useBatch = schema.eqBandSupportBatch
        val cmd = if (useBatch && schema.eqBandBatchSetCmd > 0) schema.eqBandBatchSetCmd else schema.eqBandSetCmd
        val payload = FiioPeqCodec.encodeSingleBand(schema, band, presetId, batchSet = useBatch)
        return sendCmd(transport, cbt, isBle, cmd, payload, useShell24 = useShell)
    }

    override suspend fun writeAllEqBands(
        transport: DacTransport,
        profile: DacProfile,
        bands: List<EqBand>,
        globalGainDb: Double,
        presetId: Int
    ): Boolean {
        val schema = schemaFor(profile) ?: return false
        val interval = schema.deviceCommandInterval.toLong().coerceAtLeast(25L)
        if (schema.masterGainSupport && schema.masterGainSetCmd > 0) {
            if (!setGlobalGain(transport, profile, globalGainDb)) return false
            delay(interval)
        }
        for (band in bands) {
            if (!writeEqBand(transport, profile, band, globalGainDb, presetId)) return false
            delay(interval)
        }
        return true
    }

    override suspend fun setEqEnabled(transport: DacTransport, profile: DacProfile, enabled: Boolean): Boolean {
        val schema = schemaFor(profile) ?: return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val useShell = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema)
        val bypassId = schema.bypassPresetId
        if (schema.eqSwitchSupport && schema.eqSwitchSetCmd > 0) {
            val payload = FiioPeqCodec.encodeEqSwitch(enabled, schema.eqSwitchRuler)
            return sendCmd(transport, cbt, isBle, schema.eqSwitchSetCmd, payload, useShell24 = useShell)
        } else if (bypassId != null) {
            val target = if (enabled) (lastNonBypassPresetId?.takeIf { it != bypassId } ?: schema.firstCustomPresetId) else bypassId
            return selectPreset(transport, profile, target)
        }
        return false
    }

    override suspend fun selectPreset(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean {
        val schema = schemaFor(profile) ?: return false
        if (!schema.eqPresetSupport || schema.eqPresetSetCmd <= 0) return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val useShell = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema)
        lastKnownPresetId = presetId
        if (schema.bypassPresetId == null || presetId != schema.bypassPresetId) {
            lastNonBypassPresetId = presetId
        }
        val payload = if (presetId > 255) {
            ByteArray(2).also { BytePack.u16Be(it, 0, presetId) }
        } else {
            byteArrayOf((presetId and 0xFF).toByte())
        }
        return sendCmd(transport, cbt, isBle, schema.eqPresetSetCmd, payload, useShell24 = useShell)
    }

    override suspend fun setGlobalGain(transport: DacTransport, profile: DacProfile, gainDb: Double): Boolean {
        val schema = schemaFor(profile) ?: return false
        if (!schema.masterGainSupport || schema.masterGainSetCmd <= 0) return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val useShell = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema)
        val slotId = lastKnownPresetId ?: schema.firstCustomPresetId
        val payload = FiioPeqCodec.encodeMasterGain(schema, gainDb, slotId)
        return sendCmd(transport, cbt, isBle, schema.masterGainSetCmd, payload, useShell24 = useShell)
    }

    override suspend fun setPresetName(
        transport: DacTransport,
        profile: DacProfile,
        presetId: Int,
        name: String
    ): Boolean {
        val schema = schemaFor(profile) ?: return false
        if (!schema.editEqNameSupport || schema.editEqNameSetCmd <= 0) return false
        val payload = FiioPeqCodec.buildEditEqNamePayload(schema, presetId, name) ?: return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val useShell = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema)
        return sendCmd(transport, cbt, isBle, schema.editEqNameSetCmd, payload, useShell24 = useShell)
    }

    override suspend fun refreshPresetEqState(
        transport: DacTransport,
        profile: DacProfile,
        currentState: DacDeviceState,
        presetId: Int
    ): DacDeviceState? {
        val schema = schemaFor(profile) ?: return null
        val caps = buildCapabilities(profile)
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val useShell = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema)

        val eqEnabled = if (schema.eqSwitchSupport && schema.eqSwitchGetCmd > 0) {
            requestCmd(transport, cbt, isBle, schema.eqSwitchGetCmd, useShell24 = useShell)?.let {
                FiioPeqCodec.decodeEqSwitch(it, schema.eqSwitchRuler)
            } ?: currentState.eqEnabled
        } else if (schema.bypassPresetId != null) {
            presetId != schema.bypassPresetId
        } else {
            currentState.eqEnabled
        }

        val globalGain = if (schema.masterGainSupport && schema.masterGainGetCmd > 0) {
            val mgPayload = FiioPeqCodec.buildQueryMasterGainPayload(schema, presetId)
            requestCmd(transport, cbt, isBle, schema.masterGainGetCmd, mgPayload, useShell24 = useShell)?.let { p ->
                FiioPeqCodec.decodeMasterGain(p, schema.parseRulerType)
            }?.coerceIn(caps.minGainDb, caps.maxGainDb) ?: currentState.globalGainDb
        } else currentState.globalGainDb

        val bands = readPeqBands(transport, schema, cbt, isBle, caps, useShell, presetId)
        return currentState.copy(
            eqEnabled = eqEnabled,
            activePresetId = presetId,
            globalGainDb = globalGain,
            bands = bands
        )
    }

    override suspend fun saveToFlash(transport: DacTransport, profile: DacProfile, presetId: Int): Boolean {
        val schema = schemaFor(profile) ?: return false
        if (!schema.eqSaveSupport || schema.eqSaveSetCmd <= 0) return true
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val useShell = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema)
        val targetId = if (schema.customPresetIds.isNotEmpty() && presetId !in schema.customPresetIds) {
            schema.firstCustomPresetId
        } else {
            presetId
        }
        val payload = FiioPeqCodec.buildSavePayload(schema, targetId) ?: return false
        val ok = sendCmd(transport, cbt, isBle, schema.eqSaveSetCmd, payload, useShell24 = useShell)
        delay(80)
        return ok
    }

    override suspend fun resetEq(transport: DacTransport, profile: DacProfile): Boolean {
        val schema = schemaFor(profile) ?: return false
        if (schema.eqResetSetCmd <= 0) return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val useShell = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema)
        val slot = lastNonBypassPresetId ?: lastKnownPresetId ?: schema.firstCustomPresetId
        val payload = FiioPeqCodec.buildResetPayload(schema, slot) ?: return false
        return sendCmd(transport, cbt, isBle, schema.eqResetSetCmd, payload, useShell24 = useShell)
    }

    override suspend fun setVolume(transport: DacTransport, profile: DacProfile, volume: Int): Boolean {
        val schema = schemaFor(profile) ?: return false
        val ctrl = schema.findControl("device_volume", "vol_output", "vol_of_bluetooth", "device_volume_nui") ?: return false
        if (ctrl.setCmd <= 0) return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val payload = FiioSchemaControlsHelper.encodePayload(
            ctrl,
            DacControlValue(intValue = volume.coerceIn(0, 120)),
            presetSlotId = lastKnownPresetId ?: 0
        )
        return sendCmd(transport, cbt, isBle, ctrl.setCmd, payload, useShell24 = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema))
    }

    override suspend fun setDacFilter(transport: DacTransport, profile: DacProfile, filterIndex: Int): Boolean {
        val schema = schemaFor(profile) ?: return false
        val ctrl = schema.findControl("filter", "utws17_filter") ?: return false
        if (ctrl.setCmd <= 0) return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val payload = FiioSchemaControlsHelper.encodePayload(ctrl, DacControlValue(intValue = filterIndex))
        return sendCmd(transport, cbt, isBle, ctrl.setCmd, payload, useShell24 = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema))
    }

    override suspend fun setWorkMode(transport: DacTransport, profile: DacProfile, modeIndex: Int): Boolean {
        val schema = schemaFor(profile) ?: return false
        val ctrl = schema.findControl("gain_level", "gain") ?: return false
        if (ctrl.setCmd <= 0) return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val payload = FiioSchemaControlsHelper.encodePayload(ctrl, DacControlValue(intValue = modeIndex))
        return sendCmd(transport, cbt, isBle, ctrl.setCmd, payload, useShell24 = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema))
    }

    override suspend fun setOutputMode(transport: DacTransport, profile: DacProfile, modeIndex: Int): Boolean {
        val schema = schemaFor(profile) ?: return false
        val ctrl = schema.findControl("dac_parallel", "boost_bal_out_mode", "output_estimation", "dac_work_mode") ?: return false
        if (ctrl.setCmd <= 0) return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val payload = FiioSchemaControlsHelper.encodePayload(ctrl, DacControlValue(intValue = modeIndex))
        return sendCmd(transport, cbt, isBle, ctrl.setCmd, payload, useShell24 = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema))
    }

    override suspend fun setChannelBalance(transport: DacTransport, profile: DacProfile, balance: Int): Boolean {
        val schema = schemaFor(profile) ?: return false
        val ctrl = schema.findControl("channel_balance") ?: return false
        if (ctrl.setCmd <= 0) return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val payload = encodeBtBalance(cbt, balance, ctrl.max ?: 12, ctrl.parseRulerType)
        return sendCmd(transport, cbt, isBle, ctrl.setCmd, payload, useShell24 = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema))
    }

    override suspend fun setUacMode(transport: DacTransport, profile: DacProfile, mode: Int): Boolean {
        val schema = schemaFor(profile) ?: return false
        val ctrl = schema.findControl("uac", "uac_version", "uac_version_nui") ?: return false
        if (ctrl.setCmd <= 0) return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val payload = FiioSchemaControlsHelper.encodePayload(ctrl, DacControlValue(intValue = mode))
        return sendCmd(transport, cbt, isBle, ctrl.setCmd, payload, useShell24 = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema))
    }

    override suspend fun setLedMode(transport: DacTransport, profile: DacProfile, mode: Int): Boolean {
        val schema = schemaFor(profile) ?: return false
        val ctrl = schema.findControl("led_title", "Indicator_light_switch", "indicator_light_brightness", "rgb_switch") ?: return false
        if (ctrl.setCmd <= 0) return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val payload = FiioSchemaControlsHelper.encodePayload(ctrl, DacControlValue(intValue = mode))
        return sendCmd(transport, cbt, isBle, ctrl.setCmd, payload, useShell24 = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema))
    }

    override suspend fun setExtraControl(
        transport: DacTransport,
        profile: DacProfile,
        key: String,
        value: DacControlValue
    ): Boolean {
        val schema = schemaFor(profile) ?: return false
        val ctrl = schema.controls.firstOrNull { it.key == key } ?: return false
        if (ctrl.setCmd <= 0) return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val effectiveValue = if (ctrl.allowMaxValueChange && value.dynamicMax == null) {
            value.copy(dynamicMax = lastKnownStepMax.coerceAtLeast(ctrl.max ?: 60))
        } else {
            value
        }
        if (key == "vol_control") {
            lastKnownStepMax = FiioSchemaControlsHelper.resolveStepMax(schema, effectiveValue.intValue, lastKnownStepMax)
        }
        val payload = FiioSchemaControlsHelper.encodePayload(ctrl, effectiveValue, presetSlotId = lastKnownPresetId ?: 0, isUsb = false)
        return sendCmd(transport, cbt, isBle, ctrl.setCmd, payload, useShell24 = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema))
    }

    override suspend fun setDeviceName(transport: DacTransport, profile: DacProfile, name: String): Boolean {
        val schema = schemaFor(profile) ?: return false
        if (schema.editNameSetCmd <= 0) return false
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val useShell = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema)
        val chunks = FiioSchemaControlsHelper.encodeDeviceNameChunks(
            name = clean,
            maxBytes = schema.editNameMaxBytes.coerceIn(8, 32),
            ruler = schema.editNameRuler
        )
        for (chunk in chunks) {
            if (!sendCmd(transport, cbt, isBle, schema.editNameSetCmd, chunk, useShell24 = useShell)) return false
            if (chunks.size > 1) delay(30)
        }
        return true
    }

    override suspend fun sendDeviceAction(transport: DacTransport, profile: DacProfile, cmdId: Int): Boolean {
        val schema = schemaFor(profile) ?: return false
        if (cmdId <= 0) return false
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val useShell = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema)
        val payload = if (cmdId == schema.closeDeviceCmd && schema.closeDeviceRuler != 0) byteArrayOf(0x01) else byteArrayOf()
        return sendCmd(transport, cbt, isBle, cmdId, payload, useShell24 = useShell)
    }

    override suspend fun refreshLiveTelemetry(
        transport: DacTransport,
        profile: DacProfile,
        currentState: DacDeviceState,
        includeAllControls: Boolean
    ): DacDeviceState? {
        val schema = schemaFor(profile) ?: return null
        val isBle = transport.transportType == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        val useShell = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema)

        transport.drainInput()
        var updated = currentState
        val volCtrl = schema.findControl("device_volume", "vol_output", "vol_of_bluetooth", "device_volume_nui")
        val volStepCtrl = schema.findControl("vol_control")
        val liveStepVal = if (volStepCtrl != null && volStepCtrl.getCmd > 0) {
            requestCmd(transport, cbt, isBle, volStepCtrl.getCmd, useShell24 = useShell)?.let {
                FiioSchemaControlsHelper.decodeResponse(volStepCtrl, it, isUsb = false)
            }
        } else null
        val effectiveStepVal = liveStepVal?.intValue ?: currentState.extraControlValues["vol_control"]?.intValue
        val stepMax = FiioSchemaControlsHelper.resolveStepMax(
            schema,
            effectiveStepVal,
            lastKnownStepMax.coerceAtLeast(volCtrl?.max ?: 60)
        )
        lastKnownStepMax = stepMax

        val volLimitCtrl = schema.findControl("vol_limit")
        val liveLimitVal = if (volLimitCtrl != null && volLimitCtrl.getCmd > 0) {
            requestCmd(transport, cbt, isBle, volLimitCtrl.getCmd, useShell24 = useShell)?.let { p ->
                FiioSchemaControlsHelper.decodeResponse(volLimitCtrl, p, isUsb = false)
            }
        } else null

        var nextExtras = updated.extraControlValues
        if (liveStepVal != null) {
            nextExtras = nextExtras + ("vol_control" to liveStepVal)
        }
        if (liveLimitVal != null) {
            nextExtras = nextExtras + ("vol_limit" to liveLimitVal)
        }
        val (fastExtras, fastMaxVol) = FiioSchemaControlsHelper.recomputeVolumeConstraints(schema, nextExtras, stepMax)
        val effectiveMaxVol = fastMaxVol
        updated = updated.copy(
            maxVolume = effectiveMaxVol,
            volume = updated.volume.coerceIn(0, effectiveMaxVol),
            extraControlValues = fastExtras
        )

        if (volCtrl != null && volCtrl.getCmd > 0) {
            requestCmd(transport, cbt, isBle, volCtrl.getCmd, useShell24 = useShell)?.let { p ->
                updated = updated.copy(
                    volume = FiioSchemaControlsHelper.decodeResponse(volCtrl, p, isUsb = false).intValue.coerceIn(0, effectiveMaxVol),
                    maxVolume = effectiveMaxVol
                )
            }
        }
        var presetChanged = false
        if (schema.eqPresetSupport && schema.eqPresetGetCmd > 0) {
            requestCmd(transport, cbt, isBle, schema.eqPresetGetCmd, useShell24 = useShell)?.let { p ->
                val pid = parseUintPayload(p)
                if (pid != updated.activePresetId) presetChanged = true
                lastKnownPresetId = pid
                if (schema.bypassPresetId == null || pid != schema.bypassPresetId) lastNonBypassPresetId = pid
                updated = updated.copy(activePresetId = pid)
                if (!schema.eqSwitchSupport && schema.bypassPresetId != null) {
                    updated = updated.copy(eqEnabled = pid != schema.bypassPresetId)
                }
            }
        }
        if (schema.eqSwitchSupport && schema.eqSwitchGetCmd > 0) {
            requestCmd(transport, cbt, isBle, schema.eqSwitchGetCmd, useShell24 = useShell)?.let { p ->
                updated = updated.copy(eqEnabled = FiioPeqCodec.decodeEqSwitch(p, schema.eqSwitchRuler))
            }
        }
        if ((includeAllControls || presetChanged) && schema.masterGainSupport && schema.masterGainGetCmd > 0) {
            val mgPayload = FiioPeqCodec.buildQueryMasterGainPayload(schema, updated.activePresetId)
            requestCmd(transport, cbt, isBle, schema.masterGainGetCmd, mgPayload, useShell24 = useShell)?.let { p ->
                FiioPeqCodec.decodeMasterGain(p, schema.parseRulerType)?.let { g ->
                    val caps = buildCapabilities(profile)
                    updated = updated.copy(globalGainDb = g.coerceIn(caps.minGainDb, caps.maxGainDb))
                }
            }
        }
        if (presetChanged && schema.eqBandSupport && schema.eqBandGetCmd > 0) {
            val caps = buildCapabilities(profile)
            updated = updated.copy(
                bands = readPeqBands(transport, schema, cbt, isBle, caps, useShell, updated.activePresetId)
            )
        }
        val filterCtrl = schema.findControl("filter", "utws17_filter")
        if (filterCtrl != null && filterCtrl.getCmd > 0) {
            requestCmd(transport, cbt, isBle, filterCtrl.getCmd, useShell24 = useShell)?.let { p ->
                updated = updated.copy(
                    dacFilterIndex = FiioSchemaControlsHelper.decodeResponse(filterCtrl, p, isUsb = false).intValue
                )
            }
        }
        val gainCtrl = schema.findControl("gain_level", "gain")
        if (gainCtrl != null && gainCtrl.getCmd > 0) {
            requestCmd(transport, cbt, isBle, gainCtrl.getCmd, useShell24 = useShell)?.let { p ->
                updated = updated.copy(
                    workModeIndex = FiioSchemaControlsHelper.decodeResponse(gainCtrl, p, isUsb = false).intValue
                )
            }
        }
        if (schema.batteryGetCmd > 0) {
            val isLegacyV1Battery = !useShell && cbt in setOf(1, 61444) && schema.batteryGetCmd == 0x0301
            val batVendor = if (isLegacyV1Battery) VENDOR_QUALCOMM else VENDOR_FIIO
            requestCmd(transport, cbt, isBle, schema.batteryGetCmd, vendorId = batVendor, useShell24 = useShell)?.let { p ->
                val rawB = p.lastOrNull()?.toInt()?.and(0xFF) ?: -1
                val pct = if (cbt == 61451) ((rawB - 1).coerceIn(0, 4)) * 25 else rawB
                if (pct in 0..100) updated = updated.copy(batteryPercent = pct)
            }
        }
        if (schema.codecGetCmd > 0) {
            requestCmd(transport, cbt, isBle, schema.codecGetCmd, useShell24 = useShell)?.let { p ->
                val raw = p.lastOrNull()?.toInt()?.and(0xFF)
                schema.codecOptions.firstOrNull { it.id == raw }?.label?.let { lbl ->
                    updated = updated.copy(bluetoothCodec = lbl)
                }
            }
        }
        if (includeAllControls) {
            val ampCtrl = schema.findControl("dac_parallel", "boost_bal_out_mode", "output_estimation", "dac_work_mode")
            if (ampCtrl != null && ampCtrl.getCmd > 0) {
                requestCmd(transport, cbt, isBle, ampCtrl.getCmd, useShell24 = useShell)?.let { p ->
                    updated = updated.copy(
                        outputModeIndex = FiioSchemaControlsHelper.decodeResponse(ampCtrl, p, isUsb = false).intValue
                    )
                }
            }
            val balCtrl = schema.findControl("channel_balance")
            if (balCtrl != null && balCtrl.getCmd > 0) {
                requestCmd(transport, cbt, isBle, balCtrl.getCmd, useShell24 = useShell)?.let { p ->
                    val maxBal = balCtrl.max ?: 12
                    updated = updated.copy(
                        channelBalance = decodeBtBalance(cbt, p, balCtrl.parseRulerType).coerceIn(-maxBal, maxBal)
                    )
                }
            }
            val uacCtrl = schema.findControl("uac", "uac_version", "uac_version_nui")
            if (uacCtrl != null && uacCtrl.getCmd > 0) {
                requestCmd(transport, cbt, isBle, uacCtrl.getCmd, useShell24 = useShell)?.let { p ->
                    updated = updated.copy(
                        uacMode = FiioSchemaControlsHelper.decodeResponse(uacCtrl, p, isUsb = false).intValue
                    )
                }
            }
            val (extraValues, refreshedLim) = readExtraControlsMap(transport, schema, cbt, isBle, useShell, stepMax)
            if (extraValues.isNotEmpty()) {
                val (mergedExtras, mergedMaxVol) = FiioSchemaControlsHelper.recomputeVolumeConstraints(
                    schema,
                    updated.extraControlValues + extraValues,
                    stepMax
                )
                val nextMax = refreshedLim?.takeIf { it in 20..stepMax } ?: mergedMaxVol
                updated = updated.copy(
                    maxVolume = nextMax,
                    volume = updated.volume.coerceIn(0, nextMax),
                    extraControlValues = mergedExtras
                )
            }
            if (schema.editNameGetCmd > 0) {
                val frames = if (schema.editNameRuler == 1) {
                    requestCmdMulti(transport, cbt, isBle, schema.editNameGetCmd, maxFrames = 6, useShell24 = useShell)
                } else {
                    listOfNotNull(requestCmd(transport, cbt, isBle, schema.editNameGetCmd, useShell24 = useShell))
                }
                FiioSchemaControlsHelper.decodeDeviceName(frames, schema.editNameRuler)?.let { nm ->
                    updated = updated.copy(deviceName = nm)
                }
            }
            readBatchPresetNamesBt(
                transport,
                schema,
                cbt,
                isBle,
                schema.customPresetIds,
                useShell
            )?.let { refreshedNames ->
                updated = updated.copy(customPresetNames = refreshedNames)
            }
        }
        return updated
    }

    private data class ParsedIncomingFrame(
        val cmdId: Int,
        val payload: ByteArray,
        val isCommandReplyAck: Boolean
    )

    override fun applyIncomingPacket(
        packet: ByteArray,
        profile: DacProfile,
        currentState: DacDeviceState
    ): DacDeviceState? {
        val schema = schemaFor(profile) ?: return null
        val isBle = profile.transport == TransportType.BLUETOOTH_GATT
        val cbt = schema.commandBuilderType ?: 5
        if (cbt == 0) return null
        val useShell = isV3Cmd24Shell(cbt, VENDOR_FIIO, schema)

        // Extract command + payload from unsolicited BT notification/response frame
        val parsed = extractCommandAndPayload(packet, isBle, cbt, useShell) ?: return null
        val cmdId = parsed.cmdId
        val payload = parsed.payload
        if (payload.isEmpty()) return null

        val volCtrl = schema.findControl("device_volume", "vol_output", "vol_of_bluetooth", "device_volume_nui")
        val filterCtrl = schema.findControl("filter", "utws17_filter")
        val gainCtrl = schema.findControl("gain_level", "gain")
        val balCtrl = schema.findControl("channel_balance")

        fun isWriteOnlyAck(getCmd: Int?, setCmd: Int?): Boolean =
            parsed.isCommandReplyAck && cmdId == setCmd && setCmd != getCmd

        return when (cmdId) {
            volCtrl?.getCmd, volCtrl?.setCmd -> {
                if (isWriteOnlyAck(volCtrl?.getCmd, volCtrl?.setCmd)) return null
                volCtrl?.let {
                    currentState.copy(
                        volume = FiioSchemaControlsHelper.decodeResponse(it, payload, isUsb = false).intValue.coerceIn(0, currentState.maxVolume)
                    )
                }
            }
            schema.eqPresetGetCmd, schema.eqPresetSetCmd -> {
                if (isWriteOnlyAck(schema.eqPresetGetCmd, schema.eqPresetSetCmd)) return null
                val pid = parseUintPayload(payload)
                lastKnownPresetId = pid
                if (schema.bypassPresetId == null || pid != schema.bypassPresetId) lastNonBypassPresetId = pid
                val nextEq = if (!schema.eqSwitchSupport && schema.bypassPresetId != null) {
                    pid != schema.bypassPresetId
                } else {
                    currentState.eqEnabled
                }
                currentState.copy(activePresetId = pid, eqEnabled = nextEq)
            }
            schema.eqSwitchGetCmd, schema.eqSwitchSetCmd -> {
                if (isWriteOnlyAck(schema.eqSwitchGetCmd, schema.eqSwitchSetCmd)) return null
                currentState.copy(eqEnabled = FiioPeqCodec.decodeEqSwitch(payload, schema.eqSwitchRuler))
            }
            filterCtrl?.getCmd, filterCtrl?.setCmd -> {
                if (isWriteOnlyAck(filterCtrl?.getCmd, filterCtrl?.setCmd)) return null
                filterCtrl?.let { currentState.copy(dacFilterIndex = FiioSchemaControlsHelper.decodeResponse(it, payload, isUsb = false).intValue) }
            }
            gainCtrl?.getCmd, gainCtrl?.setCmd -> {
                if (isWriteOnlyAck(gainCtrl?.getCmd, gainCtrl?.setCmd)) return null
                gainCtrl?.let { currentState.copy(workModeIndex = FiioSchemaControlsHelper.decodeResponse(it, payload, isUsb = false).intValue) }
            }
            balCtrl?.getCmd, balCtrl?.setCmd -> {
                if (isWriteOnlyAck(balCtrl?.getCmd, balCtrl?.setCmd)) return null
                balCtrl?.let {
                    val maxBal = it.max ?: 12
                    currentState.copy(channelBalance = decodeBtBalance(cbt, payload, it.parseRulerType).coerceIn(-maxBal, maxBal))
                }
            }
            schema.batteryGetCmd -> {
                val rawB = payload.last().toInt() and 0xFF
                val pct = if (cbt == 61451) ((rawB - 1).coerceIn(0, 4)) * 25 else rawB
                if (pct in 0..100) currentState.copy(batteryPercent = pct) else null
            }
            schema.codecGetCmd -> {
                val raw = payload.last().toInt() and 0xFF
                val lbl = schema.codecOptions.firstOrNull { it.id == raw }?.label ?: return null
                currentState.copy(bluetoothCodec = lbl)
            }
            else -> {
                val ctrl = schema.controls.firstOrNull { !FiioSchemaControlsHelper.isPrimaryControl(it.key) && (it.getCmd == cmdId || it.setCmd == cmdId) }
                    ?: return null
                if (isWriteOnlyAck(ctrl.getCmd, ctrl.setCmd)) return null
                val decoded = FiioSchemaControlsHelper.decodeResponse(ctrl, payload, isUsb = false)
                val stepHint = if (ctrl.key == "vol_control") {
                    FiioSchemaControlsHelper.resolveStepMax(schema, decoded.intValue, lastKnownStepMax).also {
                        lastKnownStepMax = it
                    }
                } else {
                    lastKnownStepMax
                }
                val (recomputed, effMax) = FiioSchemaControlsHelper.recomputeVolumeConstraints(
                    schema,
                    currentState.extraControlValues + (ctrl.key to decoded),
                    stepHint
                )
                currentState.copy(
                    maxVolume = effMax,
                    volume = currentState.volume.coerceIn(0, effMax),
                    extraControlValues = recomputed
                )
            }
        }
    }

    private fun extractCommandAndPayload(raw: ByteArray, isBle: Boolean, cbt: Int, useShell24: Boolean): ParsedIncomingFrame? {
        if (cbt == 61451) {
            if (raw.size < 3) return null
            val b0 = raw[0].toInt() and 0xFF
            if (b0 != 0x80 && b0 != 0x81) return null
            val sub = raw[1].toInt() and 0xFF
            val cmdId = when (sub) {
                0x0B -> 1
                0x03 -> 2
                0x04 -> 3
                0x08 -> 4
                0x07 -> 5
                0x06 -> 11
                else -> sub
            }
            val end = if ((raw.last().toInt() and 0xFF) == 0xFE) raw.size - 1 else raw.size
            val pay = if (end > 2) raw.copyOfRange(2, end) else byteArrayOf()
            return ParsedIncomingFrame(cmdId, pay, isCommandReplyAck = false)
        }
        return when (family) {
            ProtocolFamily.FIIO_BT_BR13 -> parseBr13Frame(raw)?.let { (c, p) ->
                ParsedIncomingFrame(c, p, isCommandReplyAck = false)
            }
            ProtocolFamily.FIIO_BT_GAIA -> {
                if (useShell24) {
                    val start = if (isBle) 0 else findGaiaStart(raw)
                    val base = if (isBle) start else start + 4
                    if (start < 0 || base + 6 > raw.size) return null
                    if (!isBle) {
                        val pLen = raw[start + 3].toInt() and 0xFF
                        if (start + 8 + pLen > raw.size || pLen < 2) return null
                        if (BytePack.getU16Be(raw, base) != VENDOR_QUALCOMM_V3) return null
                        val op = raw[base + 2].toInt() and 0xFF
                        if (op != OP_GAIA_V3_RX && op != 0xCE) return null
                        val rxCmd = ((raw[base + 3].toInt() and 0xFF) shl 16) or
                            ((raw[base + 4].toInt() and 0xFF) shl 8) or
                            (raw[base + 5].toInt() and 0xFF)
                        ParsedIncomingFrame(rxCmd, raw.copyOfRange(base + 6, start + 8 + pLen), isCommandReplyAck = (op == OP_GAIA_V3_RX))
                    } else {
                        if (BytePack.getU16Be(raw, 0) != VENDOR_QUALCOMM_V3) return null
                        val op = raw[2].toInt() and 0xFF
                        if (op != OP_GAIA_V3_RX && op != 0xCE) return null
                        val rxCmd = ((raw[3].toInt() and 0xFF) shl 16) or
                            ((raw[4].toInt() and 0xFF) shl 8) or
                            (raw[5].toInt() and 0xFF)
                        ParsedIncomingFrame(rxCmd, raw.copyOfRange(6, raw.size), isCommandReplyAck = (op == OP_GAIA_V3_RX))
                    }
                } else if (!isBle) {
                    val idx = findGaiaStart(raw)
                    if (idx < 0 || idx + 8 > raw.size) return null
                    val pLen = raw[idx + 3].toInt() and 0xFF
                    if (idx + 8 + pLen > raw.size) return null
                    val respCmd = BytePack.getU16Be(raw, idx + 6)
                    val isAck = (respCmd and 0x8000) != 0
                    ParsedIncomingFrame(respCmd and 0x7FFF, raw.copyOfRange(idx + 8, idx + 8 + pLen), isCommandReplyAck = isAck)
                } else null
            }
            ProtocolFamily.FIIO_BT_UNIVERSAL -> {
                val start = raw.indices.firstOrNull { it + 7 < raw.size && (raw[it].toInt() and 0xFF) == 0xF1 && (raw[it + 1].toInt() and 0xFF) == 0x10 }
                    ?: return null
                val len = raw[start + 3].toInt() and 0xFF
                if (start + len > raw.size || len < 8) return null
                if ((raw[start + len - 1].toInt() and 0xFF) != 0xFF) return null
                val expectedCrc = CrcUtils.crc8Maxim(raw, start, start + len - 2)
                if ((raw[start + len - 2].toInt() and 0xFF) != expectedCrc) return null
                val cmd24 = ((raw[start + 4].toInt() and 0xFF) shl 16) or
                    ((raw[start + 5].toInt() and 0xFF) shl 8) or
                    (raw[start + 6].toInt() and 0xFF)
                ParsedIncomingFrame(cmd24, raw.copyOfRange(start + 7, start + len - 2), isCommandReplyAck = false)
            }
            else -> null
        }
    }

    // --- Wire Packet Builders & Parsers ---

    fun buildPacket(
        cbt: Int,
        isBle: Boolean,
        cmd: Int,
        payload: ByteArray = byteArrayOf(),
        vendorId: Int = VENDOR_FIIO,
        useShell24: Boolean = isV3Cmd24Shell(cbt, vendorId)
    ): ByteArray {
        if (cbt == 61451) return buildQ5Frame(cmd, payload)
        return when (family) {
            ProtocolFamily.FIIO_BT_UNIVERSAL -> buildUniversalFrame(cmd, payload)
            ProtocolFamily.FIIO_BT_BR13 -> buildBr13Frame(cmd, payload)
            ProtocolFamily.FIIO_BT_GAIA -> {
                val gaiaVer = when (cbt) {
                    5, 7, 61443, 61447 -> 0x03 // BT_GAIA_V3_*
                    else -> 0x01 // BT_GAIA_V1_* & UTWS3
                }
                if (useShell24) {
                    // BT_GAIA_V3_4 (5), BT_GAIA_V1_4 (4), and 24-bit Qualcomm shell commands:
                    // Header [FF ver 00 (2 + payload.size)] + Vendor 00 1D + Op 0xCC + Cmd24BE + payload
                    val b2 = ((cmd ushr 16) and 0xFF).toByte()
                    val b1 = ((cmd ushr 8) and 0xFF).toByte()
                    val b0 = (cmd and 0xFF).toByte()
                    if (isBle) {
                        val out = ByteArray(6 + payload.size)
                        BytePack.u16Be(out, 0, VENDOR_QUALCOMM_V3)
                        out[2] = OP_GAIA_V3_TX.toByte()
                        out[3] = b2
                        out[4] = b1
                        out[5] = b0
                        payload.copyInto(out, 6)
                        out
                    } else {
                        val out = ByteArray(10 + payload.size)
                        out[0] = 0xFF.toByte()
                        out[1] = gaiaVer.toByte()
                        out[2] = 0x00
                        out[3] = ((2 + payload.size) and 0xFF).toByte()
                        BytePack.u16Be(out, 4, VENDOR_QUALCOMM_V3)
                        out[6] = OP_GAIA_V3_TX.toByte()
                        out[7] = b2
                        out[8] = b1
                        out[9] = b0
                        payload.copyInto(out, 10)
                        out
                    }
                } else if (cbt in setOf(3, 61446)) {
                    // BT_UTWS3: FiiO vendor SPP [FF 01 00 len 04 A5 (0x0A << 8 | cmd) payload]
                    val out = ByteArray(7 + payload.size)
                    out[0] = 0xFF.toByte()
                    out[1] = 0x01
                    out[2] = 0x00
                    out[3] = (payload.size and 0xFF).toByte()
                    out[4] = if (cmd <= 0x15) 0xA2.toByte() else 0xA0.toByte()
                    out[5] = (cmd and 0xFF).toByte()
                    out[6] = (payload.size and 0xFF).toByte()
                    payload.copyInto(out, 7)
                    out
                } else {
                    val vId = if (vendorId == VENDOR_FIIO && cbt in setOf(1, 61444)) VENDOR_QUALCOMM else vendorId
                    val is3ByteCmd = cmd > 0xFFFF
                    val fullPayload = if (is3ByteCmd) {
                        byteArrayOf((cmd and 0xFF).toByte()) + payload
                    } else {
                        payload
                    }
                    val cmd16 = if (is3ByteCmd) (cmd ushr 8) and 0xFFFF else (cmd and 0xFFFF)

                    if (isBle) {
                        val out = ByteArray(4 + fullPayload.size)
                        BytePack.u16Be(out, 0, vId)
                        BytePack.u16Be(out, 2, cmd16)
                        fullPayload.copyInto(out, 4)
                        out
                    } else {
                        val out = ByteArray(8 + fullPayload.size)
                        out[0] = 0xFF.toByte()
                        out[1] = gaiaVer.toByte()
                        out[2] = 0x00 // flags
                        out[3] = (fullPayload.size and 0xFF).toByte()
                        BytePack.u16Be(out, 4, vId)
                        BytePack.u16Be(out, 6, cmd16)
                        fullPayload.copyInto(out, 8)
                        out
                    }
                }
            }
            else -> payload
        }
    }

    private fun buildUniversalFrame(cmd: Int, payload: ByteArray): ByteArray {
        val cmdBytes = if (cmd > 0xFFFF) {
            byteArrayOf(((cmd ushr 16) and 0xFF).toByte(), ((cmd ushr 8) and 0xFF).toByte(), (cmd and 0xFF).toByte())
        } else {
            byteArrayOf(((cmd ushr 8) and 0xFF).toByte(), (cmd and 0xFF).toByte())
        }
        val totalLen = 6 + cmdBytes.size + payload.size
        val out = ByteArray(totalLen)
        out[0] = 0xF1.toByte()
        out[1] = 0x10.toByte()
        out[2] = 0x00
        out[3] = (totalLen and 0xFF).toByte()
        cmdBytes.copyInto(out, 4)
        payload.copyInto(out, 4 + cmdBytes.size)
        out[totalLen - 2] = CrcUtils.crc8Maxim(out, 0, totalLen - 2).toByte()
        out[totalLen - 1] = 0xFF.toByte()
        return out
    }

    /** Exact FiiO Control BR13 frame (`SCCommandBuilder::_buildBtBr13`): `[0x00, 0x1D, cmdHi, cmdLo, ...payload]`. */
    private fun buildBr13Frame(cmd: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        out[0] = 0x00
        out[1] = 0x1D
        out[2] = ((cmd ushr 8) and 0xFF).toByte()
        out[3] = (cmd and 0xFF).toByte()
        payload.copyInto(out, 4)
        return out
    }

    /** Exact `SCQ5CommandCodec::build` for `FIIO Q5` (`cbt == 61451`). */
    private fun buildQ5Frame(cmd: Int, payload: ByteArray): ByteArray {
        val b0 = payload.getOrNull(0) ?: 0
        val b1 = payload.getOrNull(1) ?: 0
        return when (cmd) {
            1 -> byteArrayOf(0x81.toByte(), 0x0B, 0x00, 0xFE.toByte())
            2 -> byteArrayOf(0x81.toByte(), 0x09, 0x03, 0x00, 0xFE.toByte())
            3 -> byteArrayOf(0x81.toByte(), 0x09, 0x04, 0x00, 0xFE.toByte())
            4 -> byteArrayOf(0x81.toByte(), 0x09, 0x08, 0x00, 0xFE.toByte())
            5 -> byteArrayOf(0x81.toByte(), 0x09, 0x07, 0x00, 0xFE.toByte())
            11 -> byteArrayOf(0x81.toByte(), 0x09, 0x06, 0x00, 0xFE.toByte())
            6 -> byteArrayOf(0x80.toByte(), 0x03, b0, 0xFE.toByte())
            7 -> byteArrayOf(0x80.toByte(), 0x06, b0, 0xFE.toByte())
            8 -> byteArrayOf(0x80.toByte(), 0x04, b0, b1, 0xFE.toByte())
            9 -> byteArrayOf(0x80.toByte(), 0x08, b0, 0xFE.toByte())
            10 -> byteArrayOf(0x80.toByte(), 0x07, b0, 0xFE.toByte())
            else -> byteArrayOf(0x81.toByte(), (cmd and 0xFF).toByte(), 0x00, 0xFE.toByte())
        }
    }

    private fun isV3Cmd24Shell(
        cbt: Int,
        vendorId: Int,
        schema: FiioDeviceSchema? = null
    ): Boolean {
        if (vendorId != VENDOR_FIIO || (cbt != 4 && cbt != 5)) return false
        if (schema == null) return true
        return schema.eqSwitchGetCmd > 0xFFFF ||
            schema.eqBandGetCmd > 0xFFFF ||
            schema.codecGetCmd > 0xFFFF ||
            schema.controls.any { it.getCmd > 0xFFFF || it.setCmd > 0xFFFF }
    }

    private suspend fun sendCmd(
        transport: DacTransport,
        cbt: Int,
        isBle: Boolean,
        cmd: Int,
        payload: ByteArray = byteArrayOf(),
        vendorId: Int = VENDOR_FIIO,
        useShell24: Boolean = isV3Cmd24Shell(cbt, vendorId)
    ): Boolean {
        if (cbt == 0) return false
        val pkt = buildPacket(cbt, isBle, cmd, payload, vendorId, useShell24)
        return transport.send(pkt, timeoutMs = 800)
    }

    private suspend fun requestCmd(
        transport: DacTransport,
        cbt: Int,
        isBle: Boolean,
        cmd: Int,
        payload: ByteArray = byteArrayOf(),
        vendorId: Int = VENDOR_FIIO,
        useShell24: Boolean = isV3Cmd24Shell(cbt, vendorId)
    ): ByteArray? {
        if (cbt == 0) return null
        val pkt = buildPacket(cbt, isBle, cmd, payload, vendorId, useShell24)
        if (!transport.send(pkt, timeoutMs = 800)) return null
        val targetCmd16 = if (cmd > 0xFFFF) (cmd ushr 8) and 0xFFFF else (cmd and 0xFFFF)
        repeat(4) {
            val raw = transport.receive(expectedSize = 256, timeoutMs = 550) ?: return null
            if (useShell24 && isV3ErrorFrame(raw, isBle, cmd)) return null
            val extracted = extractResponsePayload(raw, isBle, targetCmd16, cmd, cbt, vendorId, useShell24)
            if (extracted != null) return extracted
        }
        return null
    }

    private fun isV3ErrorFrame(raw: ByteArray, isBle: Boolean, fullCmd: Int): Boolean {
        val start = if (isBle) 0 else findGaiaStart(raw)
        if (start < 0) return false
        val base = if (isBle) 0 else start + 4
        if (base + 5 > raw.size) return false
        val rxVendor = BytePack.getU16Be(raw, base)
        val rxOp = raw[base + 2].toInt() and 0xFF
        val rxB2 = raw[base + 3].toInt() and 0xFF
        val rxB1 = raw[base + 4].toInt() and 0xFF
        val expB2 = (fullCmd ushr 16) and 0xFF
        val expB1 = (fullCmd ushr 8) and 0xFF
        return rxVendor == VENDOR_QUALCOMM_V3 &&
            rxOp == OP_GAIA_V3_RX &&
            rxB2 == ((expB2 or 0x80) and 0xFF) &&
            rxB1 == expB1
    }

    private suspend fun requestCmdMulti(
        transport: DacTransport,
        cbt: Int,
        isBle: Boolean,
        cmd: Int,
        payload: ByteArray = byteArrayOf(),
        maxFrames: Int = 12,
        vendorId: Int = VENDOR_FIIO,
        useShell24: Boolean = isV3Cmd24Shell(cbt, vendorId)
    ): List<ByteArray> {
        if (cbt == 0) return emptyList()
        val pkt = buildPacket(cbt, isBle, cmd, payload, vendorId, useShell24)
        if (!transport.send(pkt, timeoutMs = 800)) return emptyList()
        val targetCmd16 = if (cmd > 0xFFFF) (cmd ushr 8) and 0xFFFF else (cmd and 0xFFFF)
        val out = ArrayList<ByteArray>()
        var attempts = 0
        while (out.size < maxFrames && attempts < maxFrames + 6) {
            attempts++
            val timeout = if (out.isEmpty()) 600 else 180
            val raw = transport.receive(expectedSize = 512, timeoutMs = timeout) ?: break
            val extracted = extractResponsePayload(raw, isBle, targetCmd16, cmd, cbt, vendorId, useShell24)
            if (extracted != null) {
                out += extracted
                // If one packet already carries multiple 8-byte band records, no more frames needed
                if (extracted.size >= 16) break
            }
        }
        return out
    }

    fun extractResponsePayload(
        raw: ByteArray,
        isBle: Boolean,
        expectedCmd16: Int,
        fullCmd: Int,
        cbt: Int = 5,
        vendorId: Int = VENDOR_FIIO,
        useShell24: Boolean = isV3Cmd24Shell(cbt, vendorId)
    ): ByteArray? {
        if (cbt == 61451) return parseQ5Response(raw, fullCmd)
        when (family) {
            ProtocolFamily.FIIO_BT_GAIA -> {
                if (!useShell24 && cbt in setOf(3, 61446)) {
                    val idx = findGaiaStart(raw)
                    if (idx < 0 || idx + 7 > raw.size) return null
                    val rxCmd = raw[idx + 5].toInt() and 0xFF
                    if (rxCmd != (fullCmd and 0xFF)) return null
                    val len = raw[idx + 6].toInt() and 0xFF
                    val end = (idx + 7 + len).coerceAtMost(raw.size)
                    return raw.copyOfRange(idx + 7, end)
                }
                if (useShell24) {
                    val start = if (isBle) 0 else findGaiaStart(raw)
                    val base = if (isBle) start else start + 4
                    val minLen = if (isBle) 6 else 10
                    if (start < 0 || start + minLen > raw.size) return null
                    if (!isBle) {
                        val pLen = raw[start + 3].toInt() and 0xFF
                        if (start + 8 + pLen > raw.size || pLen < 2) return null
                        val rxVendor = BytePack.getU16Be(raw, base)
                        val rxOp = raw[base + 2].toInt() and 0xFF
                        val b2 = raw[base + 3].toInt() and 0xFF
                        val b1 = raw[base + 4].toInt() and 0xFF
                        val b0 = raw[base + 5].toInt() and 0xFF
                        val rxCmd = (b2 shl 16) or (b1 shl 8) or b0
                        if (rxVendor != VENDOR_QUALCOMM_V3 || rxOp != OP_GAIA_V3_RX) return null
                        if (rxCmd != (fullCmd and 0xFFFFFF)) return null
                        return raw.copyOfRange(base + 6, start + 8 + pLen)
                    } else {
                        val rxVendor = BytePack.getU16Be(raw, 0)
                        val rxOp = raw[2].toInt() and 0xFF
                        val b2 = raw[3].toInt() and 0xFF
                        val b1 = raw[4].toInt() and 0xFF
                        val b0 = raw[5].toInt() and 0xFF
                        val rxCmd = (b2 shl 16) or (b1 shl 8) or b0
                        if (rxVendor != VENDOR_QUALCOMM_V3 || rxOp != OP_GAIA_V3_RX || rxCmd != (fullCmd and 0xFFFFFF)) return null
                        return raw.copyOfRange(6, raw.size)
                    }
                }
                if (!isBle) {
                    val idx = findGaiaStart(raw)
                    if (idx < 0 || idx + 8 > raw.size) return null
                    val pLen = raw[idx + 3].toInt() and 0xFF
                    if (idx + 8 + pLen > raw.size) return null
                    val respCmd16 = BytePack.getU16Be(raw, idx + 6)
                    // GAIA ACKs may set bit 15 (0x8000) or GAIA V3 response bits
                    if ((respCmd16 and 0x7FFF) != (expectedCmd16 and 0x7FFF) &&
                        respCmd16 != expectedCmd16 &&
                        (respCmd16 and 0xFF7F) != (expectedCmd16 and 0xFF7F)
                    ) {
                        return null
                    }
                    val p = raw.copyOfRange(idx + 8, idx + 8 + pLen)
                    return if (fullCmd > 0xFFFF && p.isNotEmpty() && (p[0].toInt() and 0xFF) == (fullCmd and 0xFF)) {
                        p.copyOfRange(1, p.size)
                    } else if (p.isNotEmpty() && p[0] == 0.toByte() && p.size > 1) {
                        // Qualcomm GAIA ACK status byte 0x00 = SUCCESS followed by value bytes
                        p.copyOfRange(1, p.size)
                    } else p
                } else {
                    if (raw.size < 4) return null
                    return raw.copyOfRange(4, raw.size)
                }
            }
            ProtocolFamily.FIIO_BT_UNIVERSAL -> {
                val start = raw.indices.firstOrNull { it + 7 < raw.size && (raw[it].toInt() and 0xFF) == 0xF1 && (raw[it + 1].toInt() and 0xFF) == 0x10 }
                    ?: return null
                val len = raw[start + 3].toInt() and 0xFF
                if (start + len > raw.size || len < 8) return null
                if ((raw[start + len - 1].toInt() and 0xFF) != 0xFF) return null
                val expectedCrc = CrcUtils.crc8Maxim(raw, start, start + len - 2)
                if ((raw[start + len - 2].toInt() and 0xFF) != expectedCrc) return null
                val headerBytes = if (fullCmd > 0xFFFF) 7 else 6
                val rxCmd = if (fullCmd > 0xFFFF) {
                    ((raw[start + 4].toInt() and 0xFF) shl 16) or ((raw[start + 5].toInt() and 0xFF) shl 8) or (raw[start + 6].toInt() and 0xFF)
                } else {
                    ((raw[start + 4].toInt() and 0xFF) shl 8) or (raw[start + 5].toInt() and 0xFF)
                }
                if (rxCmd != (fullCmd and 0xFFFFFF)) return null
                return raw.copyOfRange(start + headerBytes, start + len - 2)
            }
            ProtocolFamily.FIIO_BT_BR13 -> {
                val parsed = parseBr13Frame(raw) ?: return null
                val normExpected = normalizeBr13Cmd((fullCmd ushr 8) and 0xFF, fullCmd and 0xFF)
                return if (parsed.first == normExpected || parsed.first == (fullCmd and 0xFFFF)) parsed.second else null
            }
            else -> return raw
        }
    }

    /** Exact `SCCommandParser::_parseBtBr13` normalization for `0x00, 0x1D` responses. */
    private fun parseBr13Frame(raw: ByteArray): Pair<Int, ByteArray>? {
        val start = raw.indices.firstOrNull { it + 3 < raw.size && raw[it] == 0.toByte() && (raw[it + 1].toInt() and 0xFF) == 0x1D }
            ?: return null
        val b2 = raw[start + 2].toInt() and 0xFF
        val b3 = raw[start + 3].toInt() and 0xFF
        val normHi = normalizeBr13Hi(b2, b3)
        val cmdId = (normHi shl 8) or b3
        val payload = if (start + 4 < raw.size) raw.copyOfRange(start + 4, raw.size) else byteArrayOf()
        return cmdId to payload
    }

    private fun normalizeBr13Cmd(hi: Int, lo: Int): Int = (normalizeBr13Hi(hi, lo) shl 8) or lo

    private fun normalizeBr13Hi(b2: Int, b3: Int): Int = when (b2) {
        0x0A, 0x0B -> 0x0A
        0x80, 0x81 -> 0x80
        0x00, 0x01 -> if (b3 == 5) 0x00 else 0x80
        else -> b2 and 0xFE
    }

    private fun parseQ5Response(raw: ByteArray, expectedCmd: Int): ByteArray? {
        if (raw.size < 3) return null
        val b0 = raw[0].toInt() and 0xFF
        if (b0 != 0x80 && b0 != 0x81) return null
        val sub = raw[1].toInt() and 0xFF
        val mappedCmd = when (sub) {
            0x0B -> 1
            0x03 -> 2
            0x04 -> 3
            0x08 -> 4
            0x07 -> 5
            0x06 -> 11
            else -> sub
        }
        if (mappedCmd != expectedCmd && sub != expectedCmd) return null
        val end = if ((raw.last().toInt() and 0xFF) == 0xFE) raw.size - 1 else raw.size
        return if (end > 2) raw.copyOfRange(2, end) else byteArrayOf()
    }

    private fun decodeBtBalance(cbt: Int, p: ByteArray, parseRulerType: Int): Int {
        if (cbt == 61451 && p.size >= 2) {
            val side = p[0].toInt() and 0xFF
            val mag = p[1].toInt() and 0xFF
            return if (mag == 0) 0 else if (side != 0) -mag else mag
        }
        return FiioSchemaControlsHelper.decodeChannelBalance(p, parseRulerType, isUsb = false)
    }

    private fun encodeBtBalance(cbt: Int, balance: Int, maxAbs: Int, parseRulerType: Int): ByteArray {
        if (cbt == 61451) {
            val v = balance.coerceIn(-maxAbs, maxAbs)
            return when {
                v < 0 -> byteArrayOf(1, (-v).toByte())
                v > 0 -> byteArrayOf(0, v.toByte())
                else -> byteArrayOf(0, 0)
            }
        }
        return FiioSchemaControlsHelper.encodeChannelBalance(balance, maxAbs, parseRulerType, isUsb = false)
    }

    private suspend fun queryBatchCache(
        transport: DacTransport,
        schema: FiioDeviceSchema,
        cbt: Int,
        isBle: Boolean,
        useShell: Boolean
    ): Map<Int, ByteArray> {
        val cmds = buildList {
            if (schema.eqSwitchSupport && schema.eqSwitchGetCmd > 0xFFFF) add(schema.eqSwitchGetCmd)
            if (schema.eqPresetSupport && schema.eqPresetGetCmd > 0xFFFF) add(schema.eqPresetGetCmd)
            if (schema.masterGainSupport && schema.masterGainGetCmd > 0xFFFF) add(schema.masterGainGetCmd)
            if (schema.batteryGetCmd > 0xFFFF) add(schema.batteryGetCmd)
            if (schema.codecGetCmd > 0xFFFF) add(schema.codecGetCmd)
            for (ctrl in schema.controls) {
                if (ctrl.getCmd > 0xFFFF) add(ctrl.getCmd)
                if (ctrl.supportCmd > 0xFFFF) add(ctrl.supportCmd)
                if (ctrl.queryCmd > 0xFFFF) add(ctrl.queryCmd)
            }
        }
        if (cmds.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Int, ByteArray>()
        for (reqPayload in FiioUsbCodecs.buildBatchQueryRequest(cmds, schema.batchQueryMaxPayload)) {
            val resp = requestCmd(transport, cbt, isBle, schema.batchQueryCmd, reqPayload, useShell24 = useShell)
                ?: continue
            for (item in FiioUsbCodecs.parseBatchQueryResponse(resp)) {
                if (item.status == 0 && item.payload.isNotEmpty()) {
                    out[item.cmd24] = item.payload
                }
            }
        }
        return out
    }

    private fun findGaiaStart(raw: ByteArray): Int {
        for (i in 0 until raw.size - 7) {
            if ((raw[i].toInt() and 0xFF) == 0xFF && ((raw[i + 1].toInt() and 0xFF) == 0x01 || (raw[i + 1].toInt() and 0xFF) == 0x03)) {
                return i
            }
        }
        return -1
    }

    private fun parseUintPayload(p: ByteArray): Int = when {
        p.isEmpty() -> 0
        p.size == 1 -> p[0].toInt() and 0xFF
        p.size == 2 -> BytePack.getU16Be(p, 0)
        else -> p.last().toInt() and 0xFF
    }

    companion object {
        const val VENDOR_FIIO = 0x04A5
        const val VENDOR_QUALCOMM = 0x000A
        const val VENDOR_QUALCOMM_V3 = 0x001D
        const val OP_GAIA_V3_TX = 0xCC
        const val OP_GAIA_V3_RX = 0xCD

        private val DEFAULT_LED_OPTIONS = listOf(
            NamedOption(0, "Off"),
            NamedOption(1, "On"),
            NamedOption(2, "Dim")
        )
    }
}
