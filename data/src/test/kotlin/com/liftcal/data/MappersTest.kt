package com.liftcal.data

import com.liftcal.data.db.ProfileEntity
import com.liftcal.engine.Calibration
import kotlin.test.Test
import kotlin.test.assertEquals

class MappersTest {
    @Test
    fun newProfileStartsAtEnginePriors() {
        val p = ProfileEntity(ageYears = 30, sex = "MALE", weightKg = 80.0, heightCm = 180.0)
        assertEquals(Calibration.PRIOR, p.toCalibration())
    }

    @Test
    fun calibrationRoundTrips() {
        val c = Calibration(1.1, 0.6, 1.3, 0.9, 0.95, 3)
        val p = ProfileEntity(ageYears = 30, sex = "FEMALE", weightKg = 60.0, heightCm = 165.0).withCalibration(c)
        assertEquals(c, p.toCalibration())
    }
}
