package com.runeshift.nuzbridge

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Read arbitrary emulator memory on demand, for a desktop probe session.
 *
 * RetroArch's command listener binds to LOOPBACK on Android, so nothing off the
 * device can talk to it — which is what makes profiling a new hack painful. It
 * previously meant capturing RAM snapshots by hand and pulling the files over
 * USB. The bridge is already on the device and already speaks that protocol, so
 * it can relay reads instead: the desktop asks this over the WebSocket, and a
 * probe becomes live rather than a diff of static dumps.
 *
 * Deliberately NOT wired into the producer's socket — a probe must never
 * interfere with the poll loop's timing or steal its replies.
 */
object MemoryRelay {

    /** Reads [len] bytes at [at]; null if RetroArch does not answer. */
    fun read(at: Long, len: Int): ByteArray? {
        if (len <= 0 || len > 4096) return null
        val sock = try { DatagramSocket().apply { soTimeout = 1500 } } catch (e: Exception) { return null }
        try {
            val out = ByteArray(len)
            var got = 0
            // RetroArch truncates large replies, so chunk. 256 keeps each reply
            // inside a single datagram with room to spare.
            while (got < len) {
                val want = minOf(256, len - got)
                val chunk = readOnce(sock, at + got, want) ?: return null
                if (chunk.isEmpty()) return null
                System.arraycopy(chunk, 0, out, got, minOf(chunk.size, len - got))
                got += chunk.size
            }
            return out
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun readOnce(sock: DatagramSocket, at: Long, len: Int): ByteArray? {
        val cmd = "READ_CORE_MEMORY ${at.toString(16)} $len".toByteArray()
        val buf = ByteArray(16 + len * 3 + 64)
        val pkt = DatagramPacket(buf, buf.size)
        return try {
            sock.send(DatagramPacket(cmd, cmd.size, InetAddress.getByName("127.0.0.1"), 55355))
            sock.receive(pkt)
            val parts = String(pkt.data, 0, pkt.length).trim().split(" ")
            if (parts.size < 3 || parts[2] == "-1") null
            else ByteArray(parts.size - 2) { i -> parts[i + 2].toInt(16).toByte() }
        } catch (e: Exception) { null }
    }

    fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("0123456789abcdef"[(x.toInt() shr 4) and 0xF])
                       .append("0123456789abcdef"[x.toInt() and 0xF])
        return sb.toString()
    }
}
