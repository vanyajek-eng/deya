package com.powerhub.deya

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ThreadLocalRandom

/**
 * Solarman V5 local transport. It wraps Modbus RTU requests in V5 TCP frames (port 8899).
 * Frame layout follows the reverse-engineered public protocol reference; logger compatibility
 * can differ by firmware.
 */
class SolarmanV5Client(
    private val host: String,
    private val loggerSerial: String,
    private val port: Int = 8899,
    private val unitId: Int = 1
) {
    private val serial: Long = loggerSerial.toLongOrNull()
        ?.takeIf { it in 1..0xFFFF_FFFFL }
        ?: error("Вкажи коректний серійний номер Wi‑Fi логера")

    fun readHoldingRegisters(start: Int, count: Int): List<Int> {
        require(start in 0..65535 && count in 1..100)
        val modbus = ByteArrayOutputStream().apply {
            write(unitId)
            write(3)
            write(start ushr 8); write(start and 0xff)
            write(count ushr 8); write(count and 0xff)
            val crc = crc16(toByteArray())
            write(crc and 0xff); write(crc ushr 8)
        }.toByteArray()
        val request = buildV5Request(modbus)

        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), 2500)
            socket.soTimeout = 3500
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            output.write(request)
            output.flush()

            val header = ByteArray(11)
            input.readFully(header)
            require((header[0].toInt() and 0xff) == 0xA5) { "Логер повернув невідому V5-рамку" }
            val payloadLength = (header[1].toInt() and 0xff) or ((header[2].toInt() and 0xff) shl 8)
            require(payloadLength in 15..512) { "Некоректна довжина відповіді логера" }
            val tail = ByteArray(payloadLength + 2)
            input.readFully(tail)
            val frame = header + tail
            require((frame.last().toInt() and 0xff) == 0x15) { "Неповна відповідь логера" }
            val checksumIndex = frame.size - 2
            val checksum = (1 until checksumIndex).sumOf { frame[it].toInt() and 0xff } and 0xff
            require(checksum == (frame[checksumIndex].toInt() and 0xff)) { "Помилка контрольної суми Solarman V5" }

            val payloadOffset = 11
            val modbusOffset = payloadOffset + 14
            require(payloadLength >= 14 + 5) { "Порожня відповідь Modbus" }
            val unit = frame[modbusOffset].toInt() and 0xff
            val function = frame[modbusOffset + 1].toInt() and 0xff
            require(unit == unitId) { "Відповідь має інший Modbus Unit ID" }
            if (function and 0x80 != 0) throw IllegalStateException("Інвертор повернув Modbus exception")
            require(function == 3) { "Відповідь не є FC03" }
            val byteCount = frame[modbusOffset + 2].toInt() and 0xff
            require(byteCount == count * 2) { "Інвертор повернув неповну карту регістрів" }
            val expectedModbusBytes = 3 + byteCount + 2
            require(modbusOffset + expectedModbusBytes <= checksumIndex) { "Обрізана Modbus відповідь" }
            val modbusBytes = frame.copyOfRange(modbusOffset, modbusOffset + expectedModbusBytes)
            val receivedCrc = (modbusBytes[modbusBytes.size - 2].toInt() and 0xff) or
                ((modbusBytes.last().toInt() and 0xff) shl 8)
            require(crc16(modbusBytes.copyOf(modbusBytes.size - 2)) == receivedCrc) { "Помилка Modbus CRC" }

            return List(count) { i ->
                val offset = modbusOffset + 3 + i * 2
                ((frame[offset].toInt() and 0xff) shl 8) or (frame[offset + 1].toInt() and 0xff)
            }
        }
    }

    fun readTelemetry(config: AppConfig): Telemetry {
        require(!config.threePhase) {
            "Для трифазного Deye потрібно підтвердити модель і карту регістрів перед читанням."
        }
        val registers = listOf(config.gridRegister, config.loadRegister, config.voltageRegister, config.socRegister)
        val start = registers.minOrNull() ?: error("Карта регістрів порожня")
        val end = registers.maxOrNull() ?: start
        require(end - start < 100) { "Регістрна карта надто розріджена" }
        val values = readHoldingRegisters(start, end - start + 1)
        fun value(register: Int) = values[register - start]
        val gridVolts = value(config.gridRegister) / 10f
        return Telemetry(
            soc = value(config.socRegister).toFloat(),
            batteryVoltage = value(config.voltageRegister) / 100f,
            loadKw = value(config.loadRegister) / 1000f,
            gridPresent = gridVolts >= 80f,
            source = "SOLARMAN_V5",
            raw = "local:$host:$port"
        )
    }

    private fun buildV5Request(modbus: ByteArray): ByteArray {
        val payloadLength = 15 + modbus.size
        val frame = ByteArrayOutputStream()
        frame.write(0xA5)
        writeLe(frame, payloadLength.toLong(), 2)
        writeLe(frame, 0x4510, 2)
        writeLe(frame, ThreadLocalRandom.current().nextInt(1, 255).toLong(), 1)
        writeLe(frame, 0, 1)
        writeLe(frame, serial, 4)
        frame.write(0x02)
        writeLe(frame, 0, 2) // sensor type
        writeLe(frame, 0, 4) // logger working time
        writeLe(frame, 0, 4) // logger power-on time
        writeLe(frame, 0, 4) // logger offset time
        frame.write(modbus)
        val bytes = frame.toByteArray()
        val checksum = (1 until bytes.size).sumOf { bytes[it].toInt() and 0xff } and 0xff
        frame.write(checksum)
        frame.write(0x15)
        return frame.toByteArray()
    }

    private fun writeLe(out: ByteArrayOutputStream, value: Long, size: Int) {
        repeat(size) { shift -> out.write(((value ushr (shift * 8)) and 0xff).toInt()) }
    }

    private fun crc16(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 0xff)
            repeat(8) { crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xffff
    }
}
