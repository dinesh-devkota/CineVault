package com.dineshdevkota.cinevault.smb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NasBrowserTest {
    @Test fun showsDiskSharesIncludingUnicodeNamesAndDfsFlags() {
        assertTrue(NasBrowser.visibleDiskShare("Media", 0))
        assertTrue(NasBrowser.visibleDiskShare("चलचित्र", 0))
        assertTrue(NasBrowser.visibleDiskShare("Distributed Movies", 0x02000000))
    }

    @Test fun excludesAdministrativeAndNonDiskShares() {
        assertFalse(NasBrowser.visibleDiskShare("ADMIN$", Int.MIN_VALUE))
        assertFalse(NasBrowser.visibleDiskShare("IPC$", 3))
        assertFalse(NasBrowser.visibleDiskShare("Printer", 1))
        assertFalse(NasBrowser.visibleDiskShare("", 0))
    }
}
