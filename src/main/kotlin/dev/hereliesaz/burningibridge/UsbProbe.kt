package dev.hereliesaz.burningibridge

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class UsbMode(val label: String) {
    DFU("DFU MODE"), BRIDGE_OS("BRIDGEOS"), RECOVERY("RECOVERY"),
    OTHER_APPLE("APPLE USB"), DISCONNECTED("NOT DETECTED"), UNKNOWN("SCAN FAILED")
}
data class UsbReading(val mode: UsbMode, val detail: String, val checkedAt: Long = System.currentTimeMillis())

/** Read-only USB detection. Detects Apple DFU 05ac:1227 and running iBridge 05ac:8600. */
object UsbProbe {
    fun parseLinux(text: String): UsbMode {
        val lines = text.lines().filter { it.contains(Regex("(?i)05ac:")) }
        if (lines.any { it.contains(Regex("(?i)05ac:1227\\b")) }) return UsbMode.DFU
        if (lines.any { it.contains(Regex("(?i)05ac:8600\\b")) }) return UsbMode.BRIDGE_OS
        if (lines.any { it.contains(Regex("(?i)05ac:(128[0-3]|12a[0-9])\\b")) }) return UsbMode.RECOVERY
        return if (lines.isNotEmpty()) UsbMode.OTHER_APPLE else UsbMode.DISCONNECTED
    }

    /** Mac ioreg uses decimal idVendor/idProduct and can show several devices at once. */
    fun parseMac(text: String): UsbMode {
        val sections = text.split(Regex("(?m)^\\s*\\+-o "))
        val product = Regex("\"idProduct\"\\s*=\\s*(\\d+)")
        val vendor = Regex("\"idVendor\"\\s*=\\s*(\\d+)")
        val apple = sections.filter { vendor.find(it)?.groupValues?.get(1) == "1452" }
        if (apple.any { product.find(it)?.groupValues?.get(1) == "4647" }) return UsbMode.DFU
        if (apple.any { product.find(it)?.groupValues?.get(1) == "34304" }) return UsbMode.BRIDGE_OS
        if (apple.any { product.find(it)?.groupValues?.get(1) in listOf("4737", "4738", "4739") }) return UsbMode.RECOVERY
        return if (apple.isNotEmpty()) UsbMode.OTHER_APPLE else UsbMode.DISCONNECTED
    }

    fun detectNow(): UsbReading {
        val command = when {
            Host.isLinux -> listOf(Host.find("lsusb")?.toString() ?: return UsbReading(UsbMode.UNKNOWN, "Install usbutils for detection"), "-d", "05ac:")
            Host.isMac -> listOf("/usr/sbin/ioreg", "-p", "IOUSB", "-l", "-w", "0")
            else -> return UsbReading(UsbMode.UNKNOWN, "Unsupported host")
        }
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val data = StringBuilder()
            val reader = Thread {
                try { process.inputStream.bufferedReader().use { input ->
                    val chars = CharArray(8192)
                    while (true) {
                        val n = input.read(chars)
                        if (n < 0) break
                        if (data.length < 1_000_000) data.append(chars, 0, n)
                    }
                } } catch (_: Exception) { }
            }
            reader.isDaemon = true
            reader.start()
            if (!process.waitFor(6, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return UsbReading(UsbMode.UNKNOWN, "USB scan timed out")
            }
            reader.join(1000)
            if (process.exitValue() != 0) return UsbReading(UsbMode.UNKNOWN, "USB tool returned " + process.exitValue())
            val mode = if (Host.isLinux) parseLinux(data.toString()) else parseMac(data.toString())
            UsbReading(mode, when (mode) {
                UsbMode.DFU -> "Apple DFU detected (05ac:1227). Confirm this is the connected T2."
                UsbMode.BRIDGE_OS -> "iBridge running (05ac:8600). DFU mode is not active."
                UsbMode.RECOVERY -> "Recovery USB detected; not DFU."
                UsbMode.OTHER_APPLE -> "Apple USB device present; T2 DFU not detected."
                UsbMode.DISCONNECTED -> "No Apple USB device detected."
                UsbMode.UNKNOWN -> "USB detection unavailable."
            })
        } catch (error: Exception) {
            UsbReading(UsbMode.UNKNOWN, error.message ?: "Device probe error")
        }
    }
}

class DeviceMonitor(private val onChange: (UsbReading) -> Unit) : AutoCloseable {
    private val open = AtomicBoolean(true)
    private val executor = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "t2-usb-scan").also { thread -> thread.isDaemon = true }
    }
    @Volatile var lastReading: UsbReading = UsbReading(UsbMode.UNKNOWN, "Scanning")
        private set

    init {
        executor.scheduleWithFixedDelay({
            if (!open.get()) return@scheduleWithFixedDelay
            val reading = UsbProbe.detectNow()
            val old = lastReading
            lastReading = reading
            onChange(reading)
            if (reading.mode != old.mode) {
                // Changes are reflected in the UI; verbose probes are not logged every 3 seconds.
            }
        }, 0, 3, TimeUnit.SECONDS)
    }

    fun refresh() {
        executor.submit {
            val next = UsbProbe.detectNow()
            lastReading = next
            onChange(next)
        }
    }
    override fun close() {
        open.set(false)
        executor.shutdownNow()
    }
}
