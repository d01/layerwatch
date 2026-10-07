package app.bambumon.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CushionPolicyTest {
    @Test fun freezesGrowCushionUpToMax() {
        val p = CushionPolicy()
        p.onStart(0)
        repeat(10) { p.onFreeze(it * 1_000L) }
        assertEquals(3_000, p.cushionMs)
    }

    @Test fun calmShrinksBackToMin() {
        val p = CushionPolicy(calmMs = 1_000)
        p.onStart(0)
        p.onFreeze(0); p.onFreeze(0) // 2000
        p.onTick(1_000, 0)
        assertEquals(1_750, p.cushionMs)
        p.onTick(1_500, 0) // not calm long enough since last change
        assertEquals(1_750, p.cushionMs)
        (2..20).forEach { p.onTick(it * 1_000L, 0) }
        assertEquals(1_000, p.cushionMs)
    }

    @Test fun catchUpHasHysteresis() {
        val p = CushionPolicy()
        p.onStart(0)
        assertEquals(1f, p.onTick(1, 1_500), 0f)      // within cushion + 800
        assertEquals(1.08f, p.onTick(2, 1_900), 0f)   // too far behind live
        assertEquals(1.08f, p.onTick(3, 1_400), 0f)   // still draining
        assertEquals(1f, p.onTick(4, 1_150), 0f)      // back near the cushion
    }
}
