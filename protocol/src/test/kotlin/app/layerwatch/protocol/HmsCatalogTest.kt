package app.layerwatch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HmsCatalogTest {
    /** A real auto-refill alert from an X1C: attr 0x07002000, code 0x00030002. */
    private val autoRefill = HmsAlert(117448704, 196610)

    @Test fun describesTheRealAutoRefillAlert() {
        assertEquals("0700_2000_0003_0002", autoRefill.id)
        assertEquals(
            "AMS A Slot 1 filament has run out and automatically switched to the slot with the same filament.",
            HmsCatalog.describe(autoRefill),
        )
        assertEquals(HmsSeverity.Common, autoRefill.level)
        assertFalse(autoRefill.level.isError)
    }

    @Test fun printErrorsAndSeverities() {
        assertEquals("The task was canceled.", HmsCatalog.describeError(0x0300400CL))
        assertTrue(HmsAlert(0x07002000, 0x00020001).level.isError) // run out, no backup: needs the user
    }

    @Test fun unknownCodesNameTheModule() {
        assertEquals("AMS notice (HMS 07FF_FFFF_0003_FFFF)", HmsCatalog.describe(HmsAlert(0x07FFFFFF, 0x0003FFFF)))
    }
}
