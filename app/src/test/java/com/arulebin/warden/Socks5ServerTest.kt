package com.arulebin.warden

import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Drives the server over real loopback sockets with hand-written SOCKS5 bytes, as the native stack would. */
class Socks5ServerTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val server = Socks5Server()
    private val port = server.start()

    @AfterTest fun stop() = server.stop()

    private fun open(): Pair<Socket, DataInputStream> {
        val s = Socket(loopback, port).apply { soTimeout = 5_000 }
        val input = DataInputStream(s.getInputStream())
        s.getOutputStream().write(byteArrayOf(5, 1, 0))
        assertContentEquals(byteArrayOf(5, 0), ByteArray(2).also(input::readFully))
        return s to input
    }

    private fun request(s: Socket, cmd: Int, atyp: Int, addr: ByteArray, port: Int) =
        s.getOutputStream().write(byteArrayOf(5, cmd.toByte(), 0, atyp.toByte()) + addr +
            byteArrayOf((port ushr 8).toByte(), port.toByte()))

    /** Reads a reply; returns (REP, bound port). */
    private fun readReply(input: DataInputStream): Pair<Int, Int> {
        assertEquals(5, input.readUnsignedByte())
        val rep = input.readUnsignedByte()
        input.readUnsignedByte()
        val len = if (input.readUnsignedByte() == 4) 16 else 4
        input.skipBytes(len)
        return rep to input.readUnsignedShort()
    }

    @Test fun `CONNECT relays both ways and passes on a half-close`() {
        val echo = ServerSocket(0, 1, loopback)
        thread {
            echo.accept().use { c -> c.getInputStream().copyTo(c.getOutputStream()) } // echoes until EOF, then closes
        }
        val (s, input) = open()
        request(s, cmd = 1, atyp = 1, addr = loopback.address, port = echo.localPort)
        assertEquals(0, readReply(input).first)

        s.getOutputStream().write("ping".toByteArray())
        s.shutdownOutput()
        assertContentEquals("ping".toByteArray(), input.readBytes()) // readBytes ends at the relayed EOF
        s.close(); echo.close()
    }

    @Test fun `CONNECT to a closed port reports failure`() {
        val unused = ServerSocket(0, 1, loopback).run { localPort.also { close() } }
        val (s, input) = open()
        request(s, cmd = 1, atyp = 1, addr = loopback.address, port = unused)
        assertEquals(4, readReply(input).first) // host unreachable / refused
        s.close()
    }

    @Test fun `domain-name targets are refused so nothing resolves outside the filter`() {
        val (s, input) = open()
        val name = "example.com".toByteArray()
        request(s, cmd = 1, atyp = 3, addr = byteArrayOf(name.size.toByte()) + name, port = 443)
        assertEquals(8, readReply(input).first)
        s.close()
    }

    @Test fun `UDP ASSOCIATE relays a datagram and its reply`() {
        val echo = DatagramSocket(0, loopback)
        thread {
            val buf = ByteArray(512)
            val p = DatagramPacket(buf, buf.size)
            echo.receive(p)
            echo.send(DatagramPacket(buf, p.length, p.socketAddress))
        }
        val (control, input) = open()
        request(control, cmd = 3, atyp = 1, addr = ByteArray(4), port = 0)
        val (rep, relayPort) = readReply(input)
        assertEquals(0, rep)

        val client = DatagramSocket(0, loopback).apply { soTimeout = 5_000 }
        val header = Socks5Server.udpHeader(loopback, echo.localPort)
        val out = header + "quic?".toByteArray()
        client.send(DatagramPacket(out, out.size, loopback, relayPort))

        val buf = ByteArray(512)
        val reply = DatagramPacket(buf, buf.size).also(client::receive)
        val (from, offset) = Socks5Server.parseUdpRequest(buf, reply.length)!!
        assertEquals(echo.localPort, from.port)
        assertContentEquals("quic?".toByteArray(), buf.copyOfRange(offset, reply.length))
        client.close(); control.close(); echo.close()
    }
}
