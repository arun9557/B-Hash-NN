package com.bnn.app.transport

enum class TransportType(val displayName: String, val emoji: String) {
    BLE("Bluetooth LE", "📶"),
    WIFI_LAN("WiFi LAN", "📡"),
    WIFI_DIRECT("WiFi Direct", "🔗"),
    WIFI_AWARE("WiFi Aware", "🌐"),
    UNKNOWN("Unknown", "❓")
}
