<p align="center">
  <img src="dongleCTRL.png" width="160" alt="dongleCTRL logo" />
</p>

<h1 align="center">dongleCTRL</h1>

<p align="center">
  <strong>Multi-protocol Android USB & Bluetooth DAC/AMP controller and Parametric EQ (PEQ) suite</strong><br/>
  USB HID · USB Serial (CDC) · Bluetooth Classic SPP · Bluetooth LE GATT<br/><br/>
  <a href="https://saweria.co/highliftdevices">Support / Donate via Saweria (Indonesia)</a><br/>
  <sub><strong>English</strong> · <a href="README.md">Bahasa Indonesia</a></sub>
</p>

<p align="center">
  <img src="screenshots/dongleCTRL_MainMenu.png" width="150" alt="DEVICES" />
  <img src="screenshots/dongleCTRL_Presets.png" width="150" alt="PRESETS" />
  <img src="screenshots/dongleCTRL_BTR17.png" width="150" alt="BTR17 Dashboard" />
  <img src="screenshots/dongleCTRL_TP35Pro.png" width="150" alt="EPZ TP35 Pro Dashboard" />
  <img src="screenshots/dongleCTRL_PEQ.png" width="150" alt="Parametric EQ" />
</p>

---

## Overview

**dongleCTRL** (`com.suprcharg3d.dongleCTRL`) is a native Android application built with Kotlin and Jetpack Compose (Material 3) for configuring USB-C DAC dongles, desktop USB DACs, and wireless Bluetooth DAC/AMPs directly from hardware protocols.

Instead of wrapping vendor web pages, dongleCTRL implements native binary protocols across **USB HID**, **USB Serial (CDC)**, **Bluetooth Classic SPP**, and **Bluetooth Low Energy (BLE GATT)** for 15 protocol families, running locally on-device with no accounts or network dependencies.

---

## Features

- **Multi-Protocol Device Discovery & Management (`DEVICES`)**
  - Auto-detection across USB-C OTG (HID + CDC/Serial) and Bluetooth (Classic SPP + BLE GATT), backed by a bundled catalog of **498 device profiles**, **76 FiiO control schemas**, and **89 device illustrations**
  - Manual protocol family override per USB device for generic or unlisted dongles
- **Interactive Parametric EQ (`Equalizer`)**
  - Real-time logarithmic frequency response curve (`20 Hz – 20 kHz`) with draggable handles and isolated per-band bell preview
  - Responsive phone layout that prevents accidental vertical scrolling while dragging nodes on the graph
  - Inline **Preamp / Global Gain** bar plus sliders, `-`/`+` steppers, and **tap-to-type exact numeric entry** for Preamp, Frequency, Gain, and Q Factor
  - Per-band **Filter Type** (`Peak`, `Low Shelf`, `High Shelf`, `Low Pass`, `High Pass`, `Band Pass`, `All Pass` depending on hardware) and bypass switch
  - Live RAM sync while dragging (`Instant Live PEQ Sync`) or manual **Apply RAM** + **Save to Flash** persistence
  - Direct **Presets** picker and **Save to Presets** buttons from the PEQ screen
- **10-Band Preset Manager & AutoEQ Import/Export (`PRESETS`)**
  - Create, edit, and delete custom **10-band** presets stored locally on your phone using the full interactive PEQ curve editor
  - Import **EqualizerAPO / AutoEQ / Squiglink `.txt`** and **FiiO XML** profiles (up to 64 parsed bands: `<= 10` bands auto-pad tail slots with flat `0.0 dB`, while imports with `> 10` active bands open a selection dialog with *First 10* and *Strongest* quick picks)
  - Export any saved preset to standard AutoEQ / EqualizerAPO `.txt` format
  - **Smart Hardware Apply Flow**:
    - Prompts you to pick a **Target DAC** when multiple USB/Bluetooth DACs are available
    - Prompts you to pick a **Custom User Slot** on multi-slot DACs (e.g., FiiO `USER1`–`USER10`), and automatically switches to a writable slot before applying and saving to flash so factory presets are not overwritten
    - Compares active (non-flat, enabled) bands against the DAC's band limit: auto-fits without prompting when active bands fit (including applying a 10-band preset with 5 active bands to a 5-band DAC), or opens a band-selection prompt when active bands exceed hardware limits
- **Hardware & Schema-Driven Controls (`Dashboard`)**
  - **DAC Digital Filters**, **Gain Level**, **L/R Channel Balance**, **Hardware EQ Toggle**, **Hardware Preset Slots & Custom Slot Renaming**, **Hardware Volume** (including 60/120-step scaling on supported devices)
  - Schema-driven controls for FiiO USB & Bluetooth devices (scene modes, screen brightness/timeout, UAC mode, volume limits, RGB indicators, button gestures, battery/codec telemetry, Bluetooth renaming, hardware actions)
- **Protocol Debug Log & Themes (`SETTINGS`)**
  - Real-time raw packet trace viewer (`TX` / `RX` hex frames across USB HID, Serial, SPP, and BLE) with one-tap **Copy** and **Clear**
  - `Follow System`, `Dark`, and `Light` Material 3 themes

---

## Supported Protocols & Hardware

| Driver | `ProtocolFamily` Variants | Transport(s) | Typical Chipsets / Devices |
| :--- | :--- | :--- | :--- |
| `CbProtocol` | `CB`, `CB1300D` | USB HID (`0x4B`) | EPZ, WalkPlay, Comtrue/TTGK CB DSP dongles & surround headsets |
| `Cb1100AuProtocol` | `CB1100AU` | USB HID (`0xCB`) | Extended CB1100AU multi-filter PEQ dongles |
| `KtProtocol` | `KT02H20`, `KT0231H` | USB HID | KTMicro KT02H20 (5-band) & KT0231H (6-band) dongles |
| `Kt1213Protocol` | `KT1213` | USB HID (`0x57`) | KTMicro KT1213 / MT2000 15-band DSP devices |
| `Cx31993Protocol` | `CX31993` | USB HID | Conexant CX31993 9-band DSP dongles |
| `Gm55Protocol` | `GM55` | USB HID (`0x02`) | GM55 10-band DSP dongles |
| `FiioHidProtocol` | `FIIO_HID`, `FIIO_V2`, `FIIO_GIGAWIT` | USB HID | FiiO / JadeAudio / Snowsky USB DACs (`KA*`, `QX*`, `JA11`, `K11`/`K13 R2R`, `BTR*` USB, `BT11`/GigaWit bridge) |
| `FiioRestmomentProtocol` | `FIIO_RESTMOMENT` | USB Serial / CDC (`0xFC`/`0xFA`) | FiiO desktop units (`K15`, `K17`, `K19`) |
| `FiioBtProtocol` | `FIIO_BT_GAIA`, `FIIO_BT_UNIVERSAL`, `FIIO_BT_BR13` | Bluetooth SPP & BLE GATT | FiiO / Snowsky wireless DAC/AMPs (`BTR*`, `BR13`, `Q5`, universal FiiO BT devices) |

> **USB OTG Auto-Detection:** USB vendor filters in `usb_device_filter.xml` recognize common vendor IDs (`0x3302`, `0x2972`, `0x5AC1`, `0x1024`, `0x31B2`, `0x0A12`, `0x0D8C`, and more), alongside generic fallback detection for USB Audio + HID/CDC devices.

---

## Architecture

The project is split into three Gradle modules to keep DSP math and hardware protocol codecs independent of Android UI code:

```text
dongleCTRL
├── core-dsp/               # Pure Kotlin/JVM module
│   └── com.suprcharg3d.dongleCTRL.dsp
│       ├── BiquadCalculator.kt      # Biquad filter coefficients & combined curve evaluation
│       ├── EqModels.kt              # EqBand, FilterType, EqPreset, DefaultBands
│       ├── PeqTextParser.kt         # AutoEQ / EqualizerAPO .txt & FiiO XML import/export
│       ├── PresetBandHelper.kt      # Active-band detection, 10-band normalization & DAC adaptation
│       └── CrcUtils.kt              # Checksum / CRC helpers for wire frames
│
├── core-protocol/          # Pure Kotlin/JVM module (depends on :core-dsp)
│   └── com.suprcharg3d.dongleCTRL.protocol
│       ├── DacModels.kt             # ProtocolFamily, DacProfile, DacCapabilities, DacDeviceState
│       ├── DacProtocol.kt           # Unified hardware driver interface
│       ├── DacCatalog.kt            # Bundled device catalog matcher (VID/PID/name)
│       ├── FiioSchemaCatalog.kt     # Dynamic schema-driven FiiO control descriptors
│       ├── HidReportDescriptorParser.kt
│       ├── ProtocolRegistry.kt
│       ├── Transport.kt             # DacTransport abstraction (USB HID, Serial, BT SPP, BLE GATT)
│       └── drivers/                 # Protocol codecs & hardware drivers (CB, KT, CX, GM55, FiiO)
│
└── app/                    # Android application (Jetpack Compose Material 3)
    └── src/main/
        ├── assets/                  # dac_catalog.json, fiio_schemas.json, dac_images/
        ├── res/                     # Adaptive icon, themes, USB device filter XML
        └── kotlin/com/suprcharg3d/dongleCTRL/
            ├── DongleCtrlApp.kt
            ├── MainActivity.kt
            ├── data/                # OfflinePrefsStore, OfflinePresetStore
            ├── usb/                 # UsbDacMonitor, AndroidUsbHidTransport, AndroidUsbSerialTransport
            ├── bt/                  # BluetoothDacMonitor, AndroidBtSppTransport, AndroidBtGattTransport
            └── ui/                  # MainViewModel, screens (Devices, Presets, Dashboard, Equalizer, Settings), components, theme
```

---

## Tech Stack & Requirements

- **Application ID / Namespace:** `com.suprcharg3d.dongleCTRL`
- **Version:** `1.1.1` (`versionCode = 4`)
- **Minimum SDK:** Android 8.0 (API 26)
- **Target / Compile SDK:** Android 16 (API 36)
- **Language & Build:** Kotlin `2.2.10`, Android Gradle Plugin `9.1.1`, Java 17 toolchain
- **UI:** Jetpack Compose (`compose-bom` `2025.05.00`) + Material 3 + Material Icons Extended

---

## Building & Testing

### Prerequisites
- Android Studio (Meerkat / Narwhal or newer) or JDK 17 + Android SDK (API 36 platform installed)
- Configure `sdk.dir` in `local.properties` (excluded from Git via `.gitignore`)

### Run Unit Tests (`:core-dsp` & `:core-protocol`)
```bash
./gradlew :core-dsp:test :core-protocol:test
```
Runs `DspTests` (biquad math, `PeqTextParser` round-trip, `PresetBandHelper` 10-band/active-band mapping) and `ProtocolTests` (protocol framing, FiiO PEQ codecs, CRC, and driver encoding/decoding).

### Build Debug APK
```bash
./gradlew :app:assembleDebug
```
Output APK: `app/build/outputs/apk/debug/app-debug.apk`

### Install via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Live Bluetooth Instrumentation Smoke Test (Optional)
An instrumentation test ([`LiveBtr17BluetoothTest.kt`](app/src/androidTest/kotlin/com/suprcharg3d/dongleCTRL/LiveBtr17BluetoothTest.kt)) is included for verifying live FiiO BTR17 Bluetooth read/write/restore behavior on a connected physical phone (automatically skips if no audio-connected BTR17 is paired):
```bash
./gradlew :app:connectedDebugAndroidTest
```

---

## Permissions

- **USB Host (`android.hardware.usb.host`, optional):** Enumerates and claims USB HID / CDC interfaces when a DAC dongle is plugged in over USB-C OTG.
- **Bluetooth (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` with `neverForLocation`, legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` <= API 30):** Detects paired/nearby wireless DACs and opens SPP or BLE GATT channels. No location permission is requested.
- **Internet:** Not requested; all protocol handling and preset storage run locally.

---

## Notes & Disclaimer

> **USE AT YOUR OWN RISK — NO WARRANTY OR LIABILITY**
>
> This software communicates directly with external audio hardware over low-level USB HID, USB Serial, and Bluetooth protocols and writes DSP/EQ configuration registers and flash memory. **By using dongleCTRL, you assume all risk.** The authors and contributors are **not responsible or liable for any hardware damage, bricked devices, corrupted firmware/flash, hearing loss, or speaker/IEM damage** resulting from the use or misuse of this application.

- Protocol support is implemented via clean-room hardware analysis and testing across supported DAC families. Vendor firmware behavior varies across models and firmware revisions; double-check gain limits, preamp headroom, and custom EQ slot selections before flashing or playing at high volume on sensitive IEMs.
- Not affiliated with, endorsed by, or supported by EPZ, WalkPlay, FiiO, JadeAudio, Snowsky, KTMicro, Conexant, or any other manufacturer. All product names and trademarks belong to their respective owners.
- Prebuilt APK binaries are provided on the [GitHub Releases](https://github.com/suprcharg3d/dongleCTRL/releases) page.
