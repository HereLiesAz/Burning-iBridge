package dev.hereliesaz.burningibridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceAndWorkspaceTest {
    @Test fun linuxDfuModeIsDetected() {
        assertEquals(UsbMode.DFU, UsbProbe.parseLinux(
            "Bus 001 Device 008: ID 05ac:1227 Apple, Inc. Mobile Device (DFU Mode)"
        ))
    }
    @Test fun bridgeOsIsDifferentFromDfu() {
        assertEquals(UsbMode.BRIDGE_OS, UsbProbe.parseLinux(
            "Bus 001 Device 011: ID 05ac:8600 Apple, Inc. iBridge"
        ))
    }
    @Test fun noAppleDeviceIsDisconnected() {
        assertEquals(UsbMode.DISCONNECTED, UsbProbe.parseLinux("Bus 001 Device 001: ID 1d6b:0002 Linux Foundation"))
    }
    @Test fun macDfuModeIsDetected() {
        val ioreg = """+-o AppleUSBXHCI  <class AppleUSBXHCI>
          +-o iBridge@00100000 <class IOUSBHostDevice> {
             "idVendor" = 1452
             "idProduct" = 4647
          }"""
        assertEquals(UsbMode.DFU, UsbProbe.parseMac(ioreg))
    }
    @Test fun logChannelClassificationIsStable() {
        assertEquals("SSH", LogBook.inferChannel("SSH: authenticated control session"))
        assertEquals("DEVICE", LogBook.inferChannel("DEVICE: palera1n: Waiting for DFU"))
        assertEquals("LINK", LogBook.inferChannel("iproxy PID 12345"))
    }
    @Test fun logRedactionMasksCommonIdentifiers() {
        val redacted = LogBook.redact("ECID: 0x1234 PASSWORD=private Bearer abcdefghijklmnop")
        assertTrue(redacted.contains("ECID=[REDACTED]"))
        assertTrue(redacted.contains("PASSWORD=[REDACTED]"))
        assertTrue(redacted.contains("Bearer [REDACTED]"))
        assertTrue(!redacted.contains("private"))
    }
}
