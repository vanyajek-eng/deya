package com.powerhub.deya

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Modbus TCP FC03 reader. Register addresses are entered by the user; no Deye map is assumed.
 * Returns unsigned 16-bit register values. Apply scale/sign/word-order according to the exact manual.
 */
class ModbusTcpClient(private val host: String, private val port: Int = 502, private val unitId: Int = 1) {
    private val transaction = AtomicInteger(1)

    fun readHoldingRegisters(startAddress: Int, quantity: Int = 1): List<Int> {
        require(startAddress in 0..65535 && quantity in 1..125)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 4000)
            socket.soTimeout = 5000
            val id = transaction.getAndIncrement() and 0xffff
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            // MBAP: transaction, protocol=0, length=6, unit, FC03, start, quantity
            out.writeShort(id); out.writeShort(0); out.writeShort(6); out.writeByte(unitId)
            out.writeByte(3); out.writeShort(startAddress); out.writeShort(quantity); out.flush()
            val responseId = input.readUnsignedShort()
            val protocol = input.readUnsignedShort()
            val length = input.readUnsignedShort()
            val responseUnit = input.readUnsignedByte()
            require(responseId == id && protocol == 0 && responseUnit == unitId) { "Некоректна Modbus TCP відповідь" }
            val function = input.readUnsignedByte()
            if (function and 0x80 != 0) throw IllegalStateException("Modbus exception code ${input.readUnsignedByte()}")
            require(function == 3) { "Очікувався FC03" }
            val byteCount = input.readUnsignedByte()
            require(byteCount == quantity * 2 && length == byteCount + 3) { "Невірна довжина Modbus відповіді" }
            return List(quantity) { input.readUnsignedShort() }
        }
    }
}
