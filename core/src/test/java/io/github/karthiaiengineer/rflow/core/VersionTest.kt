package io.github.karthiaiengineer.rflow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test fun parsesTagsAndNames() {
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3"))
        assertEquals(Version(0, 1, 0), Version.parse("0.1.0"))
    }

    @Test fun refusesAnythingElse() {
        for (text in listOf("", "1.2", "v1.2.3-beta", "latest", "1.2.3.4")) assertNull(text, Version.parse(text))
    }

    @Test fun comparesNumberByNumber() {
        assertTrue(Version(0, 10, 0) > Version(0, 9, 9))
        assertTrue(Version(1, 0, 0) > Version(0, 99, 99))
        assertTrue(Version(0, 1, 1) > Version(0, 1, 0))
    }
}
