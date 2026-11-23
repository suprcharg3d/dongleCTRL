<p align="center">
  <img src="dongleCTRL.png" width="160" alt="dongleCTRL logo" />
</p>

<h1 align="center">dongleCTRL</h1>

<p align="center">
  <strong>Aplikasi pengontrol DAC/AMP USB & Bluetooth multiprotokol dan Parametric EQ (PEQ) untuk Android</strong><br/>
  USB HID · USB Serial (CDC) · Bluetooth Classic SPP · Bluetooth LE GATT<br/><br/>
  <a href="https://saweria.co/highliftdevices">Dukung / Donasi via Saweria (Indonesia)</a><br/>
  <sub><a href="README-EN.md">English</a> · <strong>Bahasa Indonesia</strong></sub>
</p>

<p align="center">
  <img src="screenshots/dongleCTRL_MainMenu.png" width="150" alt="DEVICES" />
  <img src="screenshots/dongleCTRL_Presets.png" width="150" alt="PRESETS" />
  <img src="screenshots/dongleCTRL_BTR17.png" width="150" alt="Dashboard BTR17" />
  <img src="screenshots/dongleCTRL_TP35Pro.png" width="150" alt="Dashboard EPZ TP35 Pro" />
  <img src="screenshots/dongleCTRL_PEQ.png" width="150" alt="Parametric EQ" />
</p>

---

## Ringkasan

**dongleCTRL** (`com.suprcharg3d.dongleCTRL`) adalah aplikasi Android native yang dibangun dengan Kotlin dan Jetpack Compose (Material 3) untuk mengonfigurasi dongle DAC USB-C, DAC USB desktop, dan DAC/AMP Bluetooth nirkabel langsung melalui protokol perangkat kerasnya.

Alih-alih membungkus situs web pabrikan, dongleCTRL mengimplementasikan protokol biner native melalui **USB HID**, **USB Serial (CDC)**, **Bluetooth Classic SPP**, dan **Bluetooth Low Energy (BLE GATT)** untuk 15 keluarga protokol, berjalan secara lokal di perangkat tanpa akun ataupun ketergantungan jaringan.

---

## Fitur

- **Deteksi & Manajemen Perangkat Multiprotokol (`DEVICES`)**
  - Deteksi otomatis melalui USB-C OTG (HID + CDC/Serial) dan Bluetooth (Classic SPP + BLE GATT), didukung katalog bawaan berisi **498 profil perangkat**, **76 skema kontrol FiiO**, dan **89 ilustrasi perangkat**
  - Pengaturan manual keluarga protokol per perangkat USB untuk dongle generik atau perangkat yang belum terdaftar
- **Parametric EQ Interaktif (`Equalizer`)**
  - Kurva respons frekuensi logaritmik real-time (`20 Hz – 20 kHz`) dengan titik kontrol yang dapat digeser dan pratinjau kurva masing-masing band secara terpisah
  - Tata letak responsif untuk ponsel guna mencegah guliran vertikal yang tidak disengaja saat menggeser titik di grafik
  - Bilah **Preamp / Global Gain** langsung di halaman PEQ, lengkap dengan slider, tombol `-`/`+`, serta **ketuk-untuk-ketik nilai numerik presisi** untuk Preamp, Frekuensi, Gain, dan Q Factor
  - Pilihan **Tipe Filter** per band (`Peak`, `Low Shelf`, `High Shelf`, `Low Pass`, `High Pass`, `Band Pass`, `All Pass` sesuai dukungan perangkat keras) serta sakelar bypass
  - Sinkronisasi RAM langsung saat menggeser slider (`Instant Live PEQ Sync`) atau tombol manual **Apply RAM** + **Save to Flash**
  - Tombol langsung **Presets** dan **Save to Presets** di layar edit PEQ
- **Manajer Preset 10-Band & Impor/Ekspor AutoEQ (`PRESETS`)**
  - Buat, ubah, dan hapus preset kustom **10-band** yang tersimpan lokal di ponsel menggunakan editor kurva PEQ interaktif penuh
  - Impor file **EqualizerAPO / AutoEQ / Squiglink `.txt`** dan **FiiO XML** (hingga 64 band terbaca: `<= 10` band otomatis mengisi sisa slot dengan nilai datar `0.0 dB`, sedangkan impor dengan `> 10` band aktif akan menampilkan dialog pemilihan band dengan tombol cepat *First 10* dan *Strongest*)
  - Ekspor preset apa pun kembali ke format standar AutoEQ / EqualizerAPO `.txt`
  - **Alur Apply ke Perangkat Keras yang Aman**:
    - Meminta memilih **Target DAC** jika terdapat lebih dari satu DAC USB atau Bluetooth yang terdeteksi
    - Meminta memilih **Slot Kustom Pengguna** pada DAC yang memiliki beberapa slot (misalnya FiiO `USER1`–`USER10`), dan otomatis berpindah ke slot yang dapat ditulis sebelum menerapkan serta menyimpan ke flash agar preset bawaan pabrik tidak tertimpa
    - Membaca jumlah band aktif (bukan 0 dB / tidak di-bypass) terhadap kapasitas DAC target: langsung menerapkan tanpa pop-up apabila jumlah band aktif muat di DAC (termasuk preset 10-band yang hanya memakai 5 band aktif diterapkan ke DAC 5-band), atau menampilkan dialog pemilihan band apabila melebihi batas band DAC
- **Kontrol Perangkat Keras & Berbasis Skema (`Dashboard`)**
  - **Filter Digital DAC**, **Level Gain**, **Keseimbangan Kanal L/R**, **Sakelar Hardware EQ**, **Slot Preset Hardware & Ubah Nama Slot Kustom**, serta **Volume Hardware** (termasuk skala 60/120 langkah pada perangkat yang mendukung)
  - Kontrol dinamis berbasis skema untuk perangkat FiiO USB & Bluetooth (mode scene, kecerahan/timeout layar, mode UAC, batas volume, indikator RGB, gestur tombol, telemetri baterai/codec, ubah nama Bluetooth, dan aksi perangkat keras)
- **Log Debug Protokol & Tema (`SETTINGS`)**
  - Penampil jejak paket mentah real-time (frame heksadesimal `TX` / `RX` di USB HID, Serial, SPP, dan BLE) dengan tombol **Copy** dan **Clear**
  - Tema Material 3 `Follow System`, `Dark`, dan `Light`

---

## Protokol & Perangkat Keras yang Didukung

| Driver | Varian `ProtocolFamily` | Transpor | Chipset / Perangkat Umum |
| :--- | :--- | :--- | :--- |
| `CbProtocol` | `CB`, `CB1300D` | USB HID (`0x4B`) | Dongle DSP & headset surround EPZ, WalkPlay, Comtrue/TTGK CB |
| `Cb1100AuProtocol` | `CB1100AU` | USB HID (`0xCB`) | Dongle PEQ multi-filter CB1100AU lanjutan |
| `KtProtocol` | `KT02H20`, `KT0231H` | USB HID | Dongle KTMicro KT02H20 (5-band) & KT0231H (6-band) |
| `Kt1213Protocol` | `KT1213` | USB HID (`0x57`) | Perangkat DSP 15-band KTMicro KT1213 / MT2000 |
| `Cx31993Protocol` | `CX31993` | USB HID | Dongle DSP 9-band Conexant CX31993 |
| `Gm55Protocol` | `GM55` | USB HID (`0x02`) | Dongle DSP 10-band GM55 |
| `FiioHidProtocol` | `FIIO_HID`, `FIIO_V2`, `FIIO_GIGAWIT` | USB HID | DAC USB FiiO / JadeAudio / Snowsky (`KA*`, `QX*`, `JA11`, `K11`/`K13 R2R`, `BTR*` USB, `BT11`/bridge GigaWit) |
| `FiioRestmomentProtocol` | `FIIO_RESTMOMENT` | USB Serial / CDC (`0xFC`/`0xFA`) | Unit desktop FiiO (`K15`, `K17`, `K19`) |
| `FiioBtProtocol` | `FIIO_BT_GAIA`, `FIIO_BT_UNIVERSAL`, `FIIO_BT_BR13` | Bluetooth SPP & BLE GATT | DAC/AMP nirkabel FiiO / Snowsky (`BTR*`, `BR13`, `Q5`, perangkat BT universal FiiO) |

> **Deteksi Otomatis USB OTG:** Filter vendor USB pada `usb_device_filter.xml` mengenali ID vendor umum (`0x3302`, `0x2972`, `0x5AC1`, `0x1024`, `0x31B2`, `0x0A12`, `0x0D8C`, dan lainnya), disertai deteksi cadangan generik untuk perangkat USB Audio + HID/CDC.

---

## Arsitektur

Proyek ini dibagi menjadi tiga modul Gradle agar perhitungan DSP dan codec protokol perangkat keras terpisah dari kode UI Android:

```text
dongleCTRL
├── core-dsp/               # Modul Kotlin/JVM murni
│   └── com.suprcharg3d.dongleCTRL.dsp
│       ├── BiquadCalculator.kt      # Koefisien filter biquad & evaluasi kurva gabungan
│       ├── EqModels.kt              # EqBand, FilterType, EqPreset, DefaultBands
│       ├── PeqTextParser.kt         # Impor/ekspor AutoEQ / EqualizerAPO .txt & FiiO XML
│       ├── PresetBandHelper.kt      # Deteksi band aktif, normalisasi 10-band & adaptasi ke DAC
│       └── CrcUtils.kt              # Helper checksum / CRC untuk frame protokol
│
├── core-protocol/          # Modul Kotlin/JVM murni (bergantung pada :core-dsp)
│   └── com.suprcharg3d.dongleCTRL.protocol
│       ├── DacModels.kt             # ProtocolFamily, DacProfile, DacCapabilities, DacDeviceState
│       ├── DacProtocol.kt           # Antarmuka driver perangkat keras terpadu
│       ├── DacCatalog.kt            # Pencocok katalog perangkat bawaan (VID/PID/nama)
│       ├── FiioSchemaCatalog.kt     # Deskriptor kontrol FiiO dinamis berbasis skema
│       ├── HidReportDescriptorParser.kt
│       ├── ProtocolRegistry.kt
│       ├── Transport.kt             # Abstraksi DacTransport (USB HID, Serial, BT SPP, BLE GATT)
│       └── drivers/                 # Codec protokol & driver perangkat keras (CB, KT, CX, GM55, FiiO)
│
└── app/                    # Aplikasi Android (Jetpack Compose Material 3)
    └── src/main/
        ├── assets/                  # dac_catalog.json, fiio_schemas.json, dac_images/
        ├── res/                     # Ikon adaptif, tema, XML filter perangkat USB
        └── kotlin/com/suprcharg3d/dongleCTRL/
            ├── DongleCtrlApp.kt
            ├── MainActivity.kt
            ├── data/                # OfflinePrefsStore, OfflinePresetStore
            ├── usb/                 # UsbDacMonitor, AndroidUsbHidTransport, AndroidUsbSerialTransport
            ├── bt/                  # BluetoothDacMonitor, AndroidBtSppTransport, AndroidBtGattTransport
            └── ui/                  # MainViewModel, layar (Devices, Presets, Dashboard, Equalizer, Settings), komponen, tema
```

---

## Teknologi & Persyaratan

- **Application ID / Namespace:** `com.suprcharg3d.dongleCTRL`
- **Versi:** `1.1.1` (`versionCode = 4`)
- **SDK Minimum:** Android 8.0 (API 26)
- **Target / Compile SDK:** Android 16 (API 36)
- **Bahasa & Build:** Kotlin `2.2.10`, Android Gradle Plugin `9.1.1`, toolchain Java 17
- **UI:** Jetpack Compose (`compose-bom` `2025.05.00`) + Material 3 + Material Icons Extended

---

## Membangun & Menguji

### Prasyarat
- Android Studio (Meerkat / Narwhal atau lebih baru) atau JDK 17 + Android SDK (platform API 36 terpasang)
- Konfigurasikan `sdk.dir` di `local.properties` (dikecualikan dari Git melalui `.gitignore`)

### Menjalankan Unit Test (`:core-dsp` & `:core-protocol`)
```bash
./gradlew :core-dsp:test :core-protocol:test
```
Menjalankan `DspTests` (matematika biquad, round-trip `PeqTextParser`, pemetaan 10-band & band aktif `PresetBandHelper`) dan `ProtocolTests` (framing protokol, codec PEQ FiiO, CRC, dan encoding/decoding driver).

### Membangun APK Debug
```bash
./gradlew :app:assembleDebug
```
Lokasi output APK: `app/build/outputs/apk/debug/app-debug.apk`

### Memasang via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Pengujian Instrumentasi Bluetooth Langsung (Opsional)
Sebuah pengujian instrumentasi ([`LiveBtr17BluetoothTest.kt`](app/src/androidTest/kotlin/com/suprcharg3d/dongleCTRL/LiveBtr17BluetoothTest.kt)) disertakan untuk memverifikasi baca/tulis/pulihkan Bluetooth FiiO BTR17 secara langsung di ponsel fisik yang terhubung (otomatis dilewati jika tidak ada BTR17 yang tersambung audio):
```bash
./gradlew :app:connectedDebugAndroidTest
```

---

## Izin Aplikasi

- **USB Host (`android.hardware.usb.host`, opsional):** Mendeteksi dan mengakses antarmuka USB HID / CDC saat dongle DAC dicolokkan melalui USB-C OTG.
- **Bluetooth (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` dengan `neverForLocation`, serta `BLUETOOTH` / `BLUETOOTH_ADMIN` pada Android <= API 30):** Mendeteksi DAC nirkabel yang terpasang/di sekitar dan membuka saluran SPP atau BLE GATT. Aplikasi tidak meminta izin lokasi.
- **Internet:** Tidak diminta; seluruh penanganan protokol dan penyimpanan preset dijalankan secara lokal.

---

## Catatan & Penyangkalan (Disclaimer)

> **GUNAKAN DENGAN RISIKO ANDA SENDIRI — TANPA GARANSI ATAU PERTANGGUNGJAWABAN**
>
> Perangkat lunak ini berkomunikasi langsung dengan perangkat keras audio eksternal melalui protokol tingkat rendah USB HID, USB Serial, dan Bluetooth, serta menulis register konfigurasi DSP/EQ dan memori flash perangkat. **Dengan menggunakan dongleCTRL, Anda menanggung sepenuhnya segala risiko.** Penulis dan kontributor **tidak bertanggung jawab atas segala kerusakan perangkat keras, perangkat yang mati total (brick), kerusakan firmware/flash, gangguan pendengaran, maupun kerusakan speaker/IEM** yang timbul akibat penggunaan atau penyalahgunaan aplikasi ini.

- Dukungan protokol diimplementasikan melalui analisis clean-room dan pengujian pada berbagai keluarga DAC yang didukung. Perilaku firmware pabrikan dapat berbeda antarmodel dan revisi firmware; selalu periksa kembali batas gain, headroom preamp, serta slot EQ kustom yang dipilih sebelum menyimpan ke flash atau memutar audio pada volume tinggi dengan IEM yang sensitif.
- Aplikasi ini tidak berafiliasi dengan, tidak didukung oleh, dan tidak disahkan oleh EPZ, WalkPlay, FiiO, JadeAudio, Snowsky, KTMicro, Conexant, maupun produsen lainnya. Seluruh nama produk dan merek dagang adalah milik masing-masing pemiliknya.
- File APK siap pasang tersedia di halaman [GitHub Releases](https://github.com/suprcharg3d/dongleCTRL/releases).
