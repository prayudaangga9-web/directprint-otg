package com.local.directprint

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class PrinterEndpointInfo(
    val device: UsbDevice,
    val usbInterface: UsbInterface,
    val outEndpoint: UsbEndpoint,
    val inEndpoint: UsbEndpoint?,
    val isIppOverUsb: Boolean
)

object UsbPrinterHelper {

    private const val CANON_VENDOR_ID = 0x04A9 // 1193

    fun findPrinters(usbManager: UsbManager): List<UsbDevice> {
        return usbManager.deviceList.values.filter { device ->
            isPrinterDevice(device)
        }
    }

    fun isPrinterDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_PRINTER) return true
        if (device.vendorId == CANON_VENDOR_ID) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                return true
            }
        }
        return false
    }

    fun inspectDevice(device: UsbDevice): PrinterEndpointInfo? {
        var fallback: PrinterEndpointInfo? = null

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            val isPrinterClass = intf.interfaceClass == UsbConstants.USB_CLASS_PRINTER
            if (!isPrinterClass && device.vendorId != CANON_VENDOR_ID) continue

            var outEp: UsbEndpoint? = null
            var inEp: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) {
                        outEp = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) {
                        inEp = ep
                    }
                }
            }

            if (outEp != null) {
                val isIpp = (intf.interfaceClass == UsbConstants.USB_CLASS_PRINTER
                        && intf.interfaceSubclass == 1
                        && intf.interfaceProtocol == 4)
                val info = PrinterEndpointInfo(device, intf, outEp, inEp, isIpp)
                if (isIpp) return info // Prefer IPP-over-USB when available
                if (fallback == null) fallback = info
            }
        }
        return fallback
    }

    fun describeDevice(device: UsbDevice): String {
        val mfg = device.manufacturerName?.trim().orEmpty()
        val prod = device.productName?.trim().orEmpty()
        val label = listOf(mfg, prod).filter { it.isNotEmpty() }.joinToString(" ")
        val name = if (label.isNotEmpty()) label else "USB Printer"
        val vid = String.format("%04X", device.vendorId)
        val pid = String.format("%04X", device.productId)
        return "$name (VID:$vid PID:$pid)"
    }

    fun sendPrintPayload(
        usbManager: UsbManager,
        info: PrinterEndpointInfo,
        documentBytes: ByteArray,
        mimeType: String,
        jobName: String,
        onProgress: (String) -> Unit
    ): Result<String> {
        val connection = usbManager.openDevice(info.device)
            ?: return Result.failure(IllegalStateException("Gagal membuka koneksi USB ke printer."))

        return try {
            if (!connection.claimInterface(info.usbInterface, true)) {
                return Result.failure(IllegalStateException("Interface USB printer sedang dipakai aplikasi lain."))
            }

            val payload = if (info.isIppOverUsb) {
                onProgress("Menyusun paket IPP-over-USB ($mimeType)...")
                buildHttpIppPrintJob(documentBytes, mimeType, jobName)
            } else {
                onProgress("Mengirim data cetak langsung ke USB Bulk OUT...")
                documentBytes
            }

            val chunkSize = 16384
            var offset = 0
            while (offset < payload.size) {
                val len = minOf(chunkSize, payload.size - offset)
                val chunk = payload.copyOfRange(offset, offset + len)
                val written = connection.bulkTransfer(info.outEndpoint, chunk, len, 15000)
                if (written < 0) {
                    return Result.failure(IllegalStateException("Transfer USB terputus pada byte $offset/${payload.size}."))
                }
                offset += written
                val pct = ((offset.toDouble() / payload.size.toDouble()) * 100).toInt()
                onProgress("Mengirim ke printer... $pct%")
            }

            var responseSummary = "Data terkirim (${payload.size / 1024} KB)."
            if (info.isIppOverUsb && info.inEndpoint != null) {
                val respBuf = ByteArray(4096)
                val read = connection.bulkTransfer(info.inEndpoint, respBuf, respBuf.size, 4000)
                if (read > 0) {
                    val header = String(respBuf, 0, minOf(read, 200), StandardCharsets.UTF_8)
                    val firstLine = header.lineSequence().firstOrNull()?.trim().orEmpty()
                    if (firstLine.isNotEmpty()) {
                        responseSummary = "Printer merespons: $firstLine"
                    }
                }
            } else if (!info.isIppOverUsb) {
                responseSummary = "Terkirim ke USB Raw (${payload.size / 1024} KB). Jika printer Canon klasik tidak merespons, gunakan tombol Layanan Cetak Android."
            }

            Result.success(responseSummary)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                connection.releaseInterface(info.usbInterface)
            } catch (_: Exception) {
            }
            connection.close()
        }
    }

    private fun buildHttpIppPrintJob(docBytes: ByteArray, mimeType: String, jobName: String): ByteArray {
        val ipp = ByteArrayOutputStream()
        // IPP 2.0 header
        ipp.write(0x02)
        ipp.write(0x00)
        // Operation: Print-Job (0x0002)
        ipp.write(0x00)
        ipp.write(0x02)
        // Request ID: 1
        ipp.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
        // Operation attributes tag
        ipp.write(0x01)

        writeIppAttr(ipp, 0x47, "attributes-charset", "utf-8")
        writeIppAttr(ipp, 0x48, "attributes-natural-language", "en")
        writeIppAttr(ipp, 0x45, "printer-uri", "ipp://localhost/ipp/print")
        writeIppAttr(ipp, 0x42, "requesting-user-name", "android")
        writeIppAttr(ipp, 0x42, "job-name", jobName.take(64).ifEmpty { "DirectPrint" })
        writeIppAttr(ipp, 0x49, "document-format", mimeType)

        // End of attributes tag
        ipp.write(0x03)
        // Document payload
        ipp.write(docBytes)

        val ippBody = ipp.toByteArray()
        val crlf = "\r\n"
        val httpHeader = ("POST /ipp/print HTTP/1.1" + crlf + "Host: localhost" + crlf + "Content-Type: application/ipp" + crlf + "Content-Length: " + ippBody.size + crlf + "Connection: close" + crlf + crlf).toByteArray(StandardCharsets.US_ASCII)

        val packet = ByteArrayOutputStream(httpHeader.size + ippBody.size)
        packet.write(httpHeader)
        packet.write(ippBody)
        return packet.toByteArray()
    }

    private fun writeIppAttr(out: ByteArrayOutputStream, valueTag: Int, name: String, value: String) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val valBytes = value.toByteArray(StandardCharsets.UTF_8)
        out.write(valueTag)
        out.write((nameBytes.size shr 8) and 0xFF)
        out.write(nameBytes.size and 0xFF)
        out.write(nameBytes)
        out.write((valBytes.size shr 8) and 0xFF)
        out.write(valBytes.size and 0xFF)
        out.write(valBytes)
    }
}
