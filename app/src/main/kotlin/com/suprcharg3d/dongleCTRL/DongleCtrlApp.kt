package com.suprcharg3d.dongleCTRL

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import com.suprcharg3d.dongleCTRL.bt.BluetoothDacMonitor
import com.suprcharg3d.dongleCTRL.data.OfflinePrefsStore
import com.suprcharg3d.dongleCTRL.data.OfflinePresetStore
import com.suprcharg3d.dongleCTRL.usb.UsbDacMonitor
import com.suprcharg3d.dongleCTRL.protocol.DacCatalog
import com.suprcharg3d.dongleCTRL.protocol.FiioSchemaCatalog
import com.suprcharg3d.dongleCTRL.protocol.ProtocolRegistry

class DongleCtrlApp : Application() {

    val fiioSchemas: FiioSchemaCatalog by lazy {
        val text = assets.open("fiio_schemas.json").bufferedReader().use { it.readText() }
        FiioSchemaCatalog.fromJson(text).also {
            ProtocolRegistry.installFiioSchemas(it)
        }
    }

    val catalog: DacCatalog by lazy {
        val schemas = fiioSchemas
        val text = assets.open("dac_catalog.json").bufferedReader().use { it.readText() }
        DacCatalog.fromJson(text, schemas)
    }

    val presetStore: OfflinePresetStore by lazy {
        OfflinePresetStore(this)
    }

    val prefsStore: OfflinePrefsStore by lazy {
        OfflinePrefsStore(this)
    }

    val usbMonitor: UsbDacMonitor by lazy {
        UsbDacMonitor(this, catalog, prefsStore)
    }

    val btMonitor: BluetoothDacMonitor by lazy {
        BluetoothDacMonitor(this, catalog, logSink = usbMonitor::log)
    }

    override fun onCreate() {
        super.onCreate()
        applyUsbAutoOpenSetting(prefsStore.load().autoOpenOnUsb)
    }

    fun applyUsbAutoOpenSetting(enabled: Boolean) {
        val component = ComponentName(this, "$packageName.UsbAttachAlias")
        val targetState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            if (packageManager.getComponentEnabledSetting(component) != targetState) {
                packageManager.setComponentEnabledSetting(
                    component,
                    targetState,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }
}
