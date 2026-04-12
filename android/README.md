val serviceUUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")

val advertiseData = AdvertiseData.Builder()
    .setIncludeDeviceName(true)
    .addServiceUuid(ParcelUuid(serviceUUID))   // 🔥 MOST IMPORTANT
    .build()

    - syntex updated in BLEManager.kt file 