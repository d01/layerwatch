package app.bambumon.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommonNameTest {
    @Test fun printerLeaf() =
        assertEquals("00M00A000000000", PrinterTls.commonName("CN=00M00A000000000"))

    @Test fun cnAmongOtherAttributes() =
        assertEquals("BBL CA", PrinterTls.commonName("CN=BBL CA,O=BBL Technologies Co.\\, Ltd,C=CN"))

    @Test fun escapedCommaInsideCn() =
        assertEquals("a,b", PrinterTls.commonName("O=x,CN=a\\,b"))

    @Test fun noCn() = assertNull(PrinterTls.commonName("O=x,C=CN"))
}
