package com.example.auto_measure.feature.bluetooth.data

object BleCommand {
    const val MEASURE = "AT+Measure##"
    const val STOP = "AT+Stop##"
    const val LASER_ON = "AT+Calibration=0##"
    const val LASER_OFF = "AT+Calibration=1##"
    const val BATTERY = "AT+Batt##"
    const val INFO = "AT+Info##"
    const val WAKE = "AT+WAKE##"
}

data class DistanceData(
    val line: Double,
    val comp: Double,
    val height: Double,
    val flag: Int,
)
