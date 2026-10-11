package app.layerwatch.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class HeatStateTest {
    private fun state(current: Double?, target: Double?) = Temperature(current, target).heatState

    @Test
    fun `no target is off`() {
        assertEquals(HeatState.Off, state(28.0, 0.0))
        assertEquals(HeatState.Off, state(28.0, null))
        assertEquals(HeatState.Off, state(null, null))
    }

    @Test
    fun `well below target is heating up`() {
        assertEquals(HeatState.HeatingUp, state(180.0, 255.0))
        assertEquals(HeatState.HeatingUp, state(251.9, 255.0))
    }

    @Test
    fun `a held heater wandering a few degrees is holding`() {
        // From a real X1C capture: a nozzle held at 255 read 251 to 256.
        listOf(252.0, 253.0, 255.0, 256.0).forEach { assertEquals(HeatState.Holding, state(it, 255.0)) }
        assertEquals(HeatState.Holding, state(70.0, 70.0))
    }

    @Test
    fun `above a lowered target is cooling down`() {
        // Same capture: the target dropped to 140 while the nozzle was still at 256.
        assertEquals(HeatState.CoolingDown, state(256.0, 140.0))
    }

    @Test
    fun `a target with no reading yet counts as holding, not heating`() {
        assertEquals(HeatState.Holding, state(null, 220.0))
    }
}
