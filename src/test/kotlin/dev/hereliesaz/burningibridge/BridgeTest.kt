package dev.hereliesaz.burningibridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeTest {
    @Test fun shellQuotesWhitespace() {
        assertEquals("'a b'", Bridge.quote("a b"))
    }

    @Test fun shellQuotesApostrophes() {
        assertEquals("'a'\\''b'", Bridge.quote("a'b"))
    }

    @Test fun builtInExperimentsAreReadOnly() {
        assertTrue(remotePresets.isNotEmpty())
        assertTrue(remotePresets.values.none {
            Regex("(?i)\\b(nvram\\s+-d|nvram\\s+-c|mkfs|dd\\s+of=|idevicerestore\\s+-e)\\b").containsMatchIn(it)
        })
    }

    @Test fun systemPlatformLabelsAreNotEmpty() {
        assertTrue(Host.platform.contains("/"))
        assertTrue(Host.logs.toString().contains("burning-ibridge"))
    }
}
