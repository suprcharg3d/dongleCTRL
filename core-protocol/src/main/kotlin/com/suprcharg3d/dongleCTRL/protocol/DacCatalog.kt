package com.suprcharg3d.dongleCTRL.protocol

/**
 * Offline device catalog backed by bundled `dac_catalog.json` + generic VID/class heuristics.
 */
class DacCatalog(
    val profiles: List<DacProfile>,
    val fiioSchemas: FiioSchemaCatalog? = null
) {

    fun schemaFor(profile: DacProfile): FiioDeviceSchema? =
        fiioSchemas?.let { cat ->
            profile.fiioDeviceType?.let { cat.findByDeviceType(it) }
                ?: cat.matchName(profile.model)
                ?: cat.matchName(profile.productNameMatch)
        }

    fun match(descriptor: UsbDeviceDescriptor): DacProfile? {
        if (descriptor.bluetoothAddress != null) {
            return matchBluetoothDevice(
                descriptor.productName,
                descriptor.bluetoothAddress,
                descriptor.btConnectMode,
                descriptor.btServiceUuids
            )
        }
        val rawName = descriptor.productName?.trim().orEmpty()
        val lowerName = rawName.lowercase()

        // 1. Exact or device-name-contains-catalog-name match first (handles FiiO/Tanchjim/Moondrop shared bridges)
        val usbCandidates = if (rawName.isNotEmpty()) {
            profiles.filter {
                it.transport != TransportType.BLUETOOTH_SPP &&
                    it.transport != TransportType.BLUETOOTH_GATT &&
                    it.productNameMatch != null
            }
        } else {
            emptyList()
        }
        val normalizedName = if (lowerName == "fiio dsp audio") "fiio ls-tc2" else lowerName
        if (rawName.isNotEmpty()) {
            val nameMatch = bestNameMatch(usbCandidates, normalizedName, allowReverseContains = false) { it.productNameMatch!! }
            if (nameMatch != null) return enrichWithFiioSchema(refineFiioBridge(nameMatch, descriptor))
        }

        // 2. Exact VID + PID match; prefer EPZ official definitions over generic WalkPlay aliases when duplicated
        val vidPidMatches = profiles.filter {
            it.vendorId == descriptor.vendorId && it.productId == descriptor.productId
        }
        if (vidPidMatches.isNotEmpty()) {
            val preferred = vidPidMatches.firstOrNull { it.source == "epz" } ?: vidPidMatches.first()
            return enrichWithFiioSchema(preferred)
        }

        if (rawName.isNotEmpty() && lowerName !in GENERIC_BRAND_NAMES) {
            val reverseMatch = bestNameMatch(usbCandidates, normalizedName, allowReverseContains = true) { it.productNameMatch!! }
            if (reverseMatch != null) return enrichWithFiioSchema(refineFiioBridge(reverseMatch, descriptor))
        }

        // 3. Schema-only FiiO USB fallback (if present in sc_config_default.db even if omitted from dac_catalog)
        if (rawName.isNotEmpty()) {
            val schema = fiioSchemas?.matchName(rawName)
            if (schema != null && (descriptor.vendorId == 0x2972 || rawName.contains("fiio", ignoreCase = true) || rawName.contains("jade", ignoreCase = true) || rawName.contains("snowsky", ignoreCase = true))) {
                return DacProfile(
                    id = "fiio_schema_${schema.deviceType}",
                    brand = "FiiO",
                    model = schema.name,
                    vendorId = descriptor.vendorId,
                    productId = descriptor.productId,
                    productNameMatch = schema.name,
                    protocol = if (descriptor.hasCdcInterface && !descriptor.hasHidInterface) ProtocolFamily.FIIO_RESTMOMENT else ProtocolFamily.FIIO_HID,
                    bandCount = schema.bandCount,
                    sampleRate = 48_000,
                    minGainDb = schema.gainMin,
                    maxGainDb = schema.gainMax,
                    reportId = 7,
                    transport = if (descriptor.hasCdcInterface && !descriptor.hasHidInterface) TransportType.USB_SERIAL else TransportType.USB_HID,
                    fiioDeviceType = schema.deviceType,
                    source = "fiio_schema"
                )
            }
        }

        // 4. Fallback by known vendorId or interface heuristic
        return fallbackForUnknown(descriptor)?.let { enrichWithFiioSchema(it) }
    }

    fun matchBluetoothDevice(
        deviceName: String?,
        address: String,
        connectModeHint: Int? = null,
        serviceUuids: List<String> = emptyList()
    ): DacProfile? {
        if (serviceUuids.isNotEmpty()) {
            val uuidSet = serviceUuids.map { it.lowercase() }.toSet()
            val byGattUuid = profiles.firstOrNull { profile ->
                profile.transport == TransportType.BLUETOOTH_GATT &&
                    profile.btSppUuid?.lowercase() in uuidSet
            }
            if (byGattUuid != null) {
                return byGattUuid.copy(id = "${byGattUuid.id}_${address.replace(":", "")}")
            }
        }
        val rawName = deviceName?.trim().orEmpty()
        if (rawName.isNotEmpty()) {
            val lower = rawName.lowercase()
            val btCandidates = profiles.filter {
                it.transport == TransportType.BLUETOOTH_SPP || it.transport == TransportType.BLUETOOTH_GATT
            }
            val catalogBt = bestNameMatch(btCandidates, lower) { it.productNameMatch ?: it.model }
            if (catalogBt != null) {
                return catalogBt.copy(id = "${catalogBt.id}_${address.replace(":", "")}")
            }
        }
        if (serviceUuids.isNotEmpty()) {
            val uuidSet = serviceUuids.map { it.lowercase() }.toSet()
            val byUuid = profiles.firstOrNull { profile ->
                (profile.transport == TransportType.BLUETOOTH_GATT || profile.transport == TransportType.BLUETOOTH_SPP) &&
                    profile.btSppUuid?.lowercase() in uuidSet
            }
            if (byUuid != null) {
                return byUuid.copy(id = "${byUuid.id}_${address.replace(":", "")}")
            }
        }
        val schema = fiioSchemas?.matchName(deviceName) ?: return null
        val cm = schema.connectMode ?: connectModeHint ?: 2
        if (cm == 3) return null // USB-only device
        val proto = when (schema.commandBuilderType) {
            61453 -> ProtocolFamily.FIIO_BT_BR13
            6 -> ProtocolFamily.FIIO_BT_UNIVERSAL
            else -> ProtocolFamily.FIIO_BT_GAIA
        }
        val txType = if (cm == 1) TransportType.BLUETOOTH_GATT else TransportType.BLUETOOTH_SPP
        val hasControlBuilder = (schema.commandBuilderType ?: 0) != 0
        val effectiveBandCount = if (hasControlBuilder && schema.eqBandSupport && schema.bandCount > 0) schema.bandCount else 0
        return DacProfile(
            id = "bt_fiio_${schema.deviceType}_${address.replace(":", "")}",
            brand = when {
                schema.name.contains("Jade", ignoreCase = true) -> "JadeAudio"
                schema.name.contains("SNOWSKY", ignoreCase = true) -> "SNOWSKY"
                else -> "FiiO"
            },
            model = schema.name,
            vendorId = 0x2972,
            productId = schema.deviceType,
            productNameMatch = schema.name,
            protocol = proto,
            bandCount = effectiveBandCount,
            sampleRate = 48_000,
            minGainDb = schema.gainMin,
            maxGainDb = schema.gainMax,
            reportId = 0,
            transport = txType,
            supportsDacFilter = schema.findControl("filter", "utws17_filter") != null,
            supportsMic = schema.findControl("mic_detect", "microphone_switch", "microphone_volume") != null,
            fiioDeviceType = schema.deviceType,
            source = "fiio_bt"
        )
    }

    private fun bestNameMatch(
        candidates: List<DacProfile>,
        normalizedQuery: String,
        allowReverseContains: Boolean = true,
        targetSelector: (DacProfile) -> String
    ): DacProfile? {
        candidates.firstOrNull { targetSelector(it).lowercase() == normalizedQuery }?.let { return it }
        candidates
            .filter { normalizedQuery.contains(targetSelector(it).lowercase()) }
            .maxByOrNull { targetSelector(it).length }
            ?.let { return it }
        if (!allowReverseContains) return null
        return candidates
            .filter { targetSelector(it).lowercase().contains(normalizedQuery) }
            .minByOrNull { targetSelector(it).length }
    }

    private fun enrichWithFiioSchema(profile: DacProfile): DacProfile {
        val schema = schemaFor(profile) ?: return profile
        val hasControlBuilder = (schema.commandBuilderType ?: 0) != 0
        val effectiveBandCount = if (hasControlBuilder && schema.eqBandSupport && schema.bandCount > 0) schema.bandCount else 0
        return profile.copy(
            bandCount = effectiveBandCount,
            minGainDb = schema.gainMin,
            maxGainDb = schema.gainMax,
            fiioDeviceType = schema.deviceType
        )
    }

    private fun refineFiioBridge(profile: DacProfile, descriptor: UsbDeviceDescriptor): DacProfile {
        if (profile.model == "FIIO BT11" || profile.model == "FIIO Air Link") {
            val isTengtai = descriptor.productId == 16434 || descriptor.productId == 16435 || descriptor.vendorId == 0x2972
            return if (isTengtai) profile else profile.copy(protocol = ProtocolFamily.FIIO_GIGAWIT)
        }
        if (descriptor.hasCdcInterface && !descriptor.hasHidInterface) {
            return profile.copy(protocol = ProtocolFamily.FIIO_RESTMOMENT, transport = TransportType.USB_SERIAL)
        }
        return profile
    }

    fun fallbackForUnknown(descriptor: UsbDeviceDescriptor): DacProfile? {
        return when {
            descriptor.vendorId == 0x2972 -> DacProfile(
                id = "generic_fiio_${descriptor.productId}",
                brand = "FiiO",
                model = descriptor.productName ?: "FiiO USB DAC",
                vendorId = descriptor.vendorId,
                productId = descriptor.productId,
                protocol = if (descriptor.hasCdcInterface && !descriptor.hasHidInterface) {
                    ProtocolFamily.FIIO_RESTMOMENT
                } else {
                    ProtocolFamily.FIIO_HID
                },
                bandCount = 10,
                sampleRate = 48_000,
                reportId = 7,
                transport = if (descriptor.hasCdcInterface && !descriptor.hasHidInterface) {
                    TransportType.USB_SERIAL
                } else {
                    TransportType.USB_HID
                },
                source = "heuristic"
            )
            descriptor.vendorId in WALKPLAY_VENDOR_IDS -> DacProfile(
                id = "generic_wp_${descriptor.vendorId.toString(16)}_${descriptor.productId.toString(16)}",
                brand = descriptor.manufacturerName ?: "WalkPlay / EPZ",
                model = descriptor.productName ?: "USB DSP Dongle",
                vendorId = descriptor.vendorId,
                productId = descriptor.productId,
                protocol = ProtocolFamily.CB,
                bandCount = 8,
                sampleRate = 96_000,
                reportId = 0x4B,
                supportsDacFilter = true,
                supportsMic = true,
                source = "heuristic"
            )
            descriptor.vendorId == 0x1024 && descriptor.productId == 0x8087 -> DacProfile(
                id = "gm55_fallback",
                brand = "EPZ",
                model = descriptor.productName ?: "GM55",
                vendorId = descriptor.vendorId,
                productId = descriptor.productId,
                protocol = ProtocolFamily.GM55,
                bandCount = 10,
                sampleRate = 48_000,
                reportId = 0x02,
                source = "heuristic"
            )
            descriptor.vendorId == 0x0572 -> DacProfile(
                id = "cnxt_freeman3_${descriptor.vendorId.toString(16)}_${descriptor.productId.toString(16)}",
                brand = descriptor.manufacturerName ?: "MOONDROP",
                model = descriptor.productName ?: "Freeman3 USB DAC",
                vendorId = descriptor.vendorId,
                productId = descriptor.productId,
                protocol = ProtocolFamily.MOONDROP_FREEMAN3,
                bandCount = 5,
                sampleRate = 48_000,
                reportId = 1,
                transport = TransportType.USB_HID,
                source = "moondrop"
            )
            else -> null
        }
    }

    fun genericProfileFor(descriptor: UsbDeviceDescriptor, protocol: ProtocolFamily): DacProfile {
        val defaultBands = when (protocol) {
            ProtocolFamily.KT02H20, ProtocolFamily.MOONDROP_FREEMAN3 -> 5
            ProtocolFamily.KT0231H -> 6
            ProtocolFamily.CX31993 -> 9
            ProtocolFamily.KT1213 -> 15
            ProtocolFamily.TTGK_INFO, ProtocolFamily.MOONDROP_COMTURE, ProtocolFamily.MOONDROP_WMIC -> 0
            ProtocolFamily.FIIO_RESTMOMENT -> 10
            ProtocolFamily.CB1100AU, ProtocolFamily.GM55, ProtocolFamily.FIIO_HID, ProtocolFamily.FIIO_V2 -> 10
            else -> 10
        }
        val rid = when (protocol) {
            ProtocolFamily.CB1100AU -> 0xCB
            ProtocolFamily.KT1213 -> 0x57
            ProtocolFamily.CX31993, ProtocolFamily.MOONDROP_FREEMAN3 -> 0x01
            ProtocolFamily.GM55 -> 0x02
            ProtocolFamily.FIIO_HID, ProtocolFamily.FIIO_V2, ProtocolFamily.FIIO_GIGAWIT -> 7
            ProtocolFamily.MOONDROP_COMTURE, ProtocolFamily.MOONDROP_BLE_SOURCE, ProtocolFamily.MOONDROP_WMIC -> 0
            else -> 0x4B
        }
        return DacProfile(
            id = "manual_${protocol.name.lowercase()}_${descriptor.vendorId}_${descriptor.productId}",
            brand = descriptor.manufacturerName ?: "USB Audio",
            model = descriptor.productName ?: "Custom USB DAC",
            vendorId = descriptor.vendorId,
            productId = descriptor.productId,
            protocol = protocol,
            bandCount = defaultBands,
            sampleRate = if (protocol == ProtocolFamily.CB || protocol == ProtocolFamily.MOONDROP_SPV) 96_000 else 48_000,
            reportId = rid,
            supportsSurround3d = protocol == ProtocolFamily.CB1300D,
            supportsMic = protocol == ProtocolFamily.CB || protocol == ProtocolFamily.CB1300D || protocol == ProtocolFamily.MOONDROP_SPV,
            supportsDacFilter = protocol == ProtocolFamily.CB || protocol == ProtocolFamily.FIIO_HID || protocol == ProtocolFamily.MOONDROP_SPV || protocol == ProtocolFamily.MOONDROP_COMTURE,
            source = "manual"
        )
    }

    companion object {
        private val GENERIC_BRAND_NAMES = setOf("moondrop", "tanchjim", "nicehck", "yuandao", "fiio", "epz", "walkplay")

        val WALKPLAY_VENDOR_IDS = setOf(
            0x3302, 0x0762, 0x35D8, 0x2FC6, 0x0104, 0xB44D,
            0x0661, 0x0666, 0x0D8C, 0x0663, 0x011D, 0x0909,
            0x34BE, 0x36A7, 0x373B, 0x5AC1, 0x31B2
        )

        fun fromJson(jsonText: String, fiioSchemas: FiioSchemaCatalog? = null): DacCatalog {
            val root = SimpleJson.parseObject(jsonText)
            @Suppress("UNCHECKED_CAST")
            val rawDevices = root["devices"] as? List<Map<String, Any?>> ?: emptyList()
            val profiles = rawDevices.map { obj ->
                val transport = when ((obj["transport"] as? String ?: "USB_HID").uppercase()) {
                    "USB_SERIAL" -> TransportType.USB_SERIAL
                    "BLUETOOTH_SPP" -> TransportType.BLUETOOTH_SPP
                    "BLUETOOTH_GATT" -> TransportType.BLUETOOTH_GATT
                    else -> TransportType.USB_HID
                }
                val balEnc = when ((obj["balanceEncoding"] as? String ?: "AUTO").uppercase()) {
                    "WALKPLAY_BE_MAP" -> BalanceEncoding.WALKPLAY_BE_MAP
                    "Q8_8_LE_AT_5" -> BalanceEncoding.Q8_8_LE_AT_5
                    else -> BalanceEncoding.AUTO
                }
                DacProfile(
                    id = obj["id"] as? String ?: "",
                    brand = obj["brand"] as? String ?: "Unknown",
                    model = obj["model"] as? String ?: "Unknown DAC",
                    vendorId = (obj["vendorId"] as? Number)?.toInt() ?: 0,
                    productId = (obj["productId"] as? Number)?.toInt(),
                    productNameMatch = obj["productNameMatch"] as? String,
                    protocol = ProtocolFamily.fromWireName(obj["protocol"] as? String ?: "CB"),
                    schemeNo = obj["schemeNo"] as? String,
                    bandCount = (obj["bandCount"] as? Number)?.toInt() ?: 8,
                    sampleRate = (obj["sampleRate"] as? Number)?.toInt() ?: 96_000,
                    minGainDb = (obj["minGainDb"] as? Number)?.toDouble() ?: -12.0,
                    maxGainDb = (obj["maxGainDb"] as? Number)?.toDouble() ?: 12.0,
                    reportId = (obj["reportId"] as? Number)?.toInt() ?: 0x4B,
                    transport = transport,
                    supportsSurround3d = obj["supportsSurround3d"] as? Boolean ?: false,
                    supportsMic = obj["supportsMic"] as? Boolean ?: false,
                    supportsDacFilter = obj["supportsDacFilter"] as? Boolean ?: false,
                    source = obj["source"] as? String ?: "catalog",
                    compensate2X = obj["compensate2X"] as? Boolean,
                    nonBlockingWrite = obj["nonBlockingWrite"] as? Boolean ?: false,
                    isHeadset = obj["isHeadset"] as? Boolean ?: false,
                    balanceEncoding = balEnc,
                    btSppUuid = obj["btSppUuid"] as? String
                )
            }
            return DacCatalog(profiles, fiioSchemas)
        }
    }
}
