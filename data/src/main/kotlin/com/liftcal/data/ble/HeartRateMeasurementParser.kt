package com.liftcal.data.ble

data class HeartRateMeasurement(
    val bpm: Int,
    /** null when the sensor does not report contact status. */
    val sensorContact: Boolean?,
    val energyExpendedKj: Int?,
    val rrIntervalsMs: List<Double>,
)

/** Parses the Bluetooth SIG Heart Rate Measurement characteristic (0x2A37). */
object HeartRateMeasurementParser {
    private const val FLAG_HR_UINT16 = 0x01
    private const val FLAG_CONTACT_DETECTED = 0x02
    private const val FLAG_CONTACT_SUPPORTED = 0x04
    private const val FLAG_ENERGY_PRESENT = 0x08
    private const val FLAG_RR_PRESENT = 0x10

    fun parse(value: ByteArray): HeartRateMeasurement? {
        if (value.size < 2) return null
        val flags = value[0].toInt() and 0xFF
        var i = 1
        val bpm: Int
        if (flags and FLAG_HR_UINT16 != 0) {
            if (value.size < 3) return null
            bpm = u16(value, i); i += 2
        } else {
            bpm = value[i].toInt() and 0xFF; i += 1
        }
        val contact = if (flags and FLAG_CONTACT_SUPPORTED != 0) flags and FLAG_CONTACT_DETECTED != 0 else null
        var energy: Int? = null
        if (flags and FLAG_ENERGY_PRESENT != 0 && i + 1 < value.size) {
            energy = u16(value, i); i += 2
        }
        val rr = ArrayList<Double>()
        if (flags and FLAG_RR_PRESENT != 0) {
            while (i + 1 < value.size) {
                rr += u16(value, i) * 1000.0 / 1024.0
                i += 2
            }
        }
        return HeartRateMeasurement(bpm, contact, energy, rr)
    }

    private fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
}
