package com.example.auto_measure.feature.bluetooth.data

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
)
