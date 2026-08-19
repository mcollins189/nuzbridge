package com.runeshift.nuzbridge

import android.content.Context
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-demand RAM snapshots for offline analysis: dumps EWRAM (256KB) + IWRAM
 * (32KB) over the RetroArch UDP interface into
 * Android/data/com.runeshift.nuzbridge/files/snapshots/ — pullable over USB.
 *
 * File format "NUZSNAP1": magic(8) then per region [u32le base][u32le len][data].
 * Capture at decision moments (before/after Time Turner, mid-battle, on a
 * misbehaving map) and hand the pair to the desktop tooling to diff — the
 * same technique that located the in-game clock, without a PC playthrough.
 */
object SnapshotTool {
    private val REGIONS = listOf(0x02000000L to 0x40000, 0x03000000L to 0x8000)
    // Captures never overwrite (the filename carries a per-second timestamp);
    // only this many are RETAINED. Raised from 12 so a multi-state hunt can be
    // taken without silently pruning the earlier reference captures it will be
    // diffed against. 24 × 288KB ≈ 7MB — nothing on a handheld.
    private const val KEEP = 24

    @Volatile var status: String = "—"
    @Volatile var capturing = false

    fun capture(ctx: Context, label: String, onDone: () -> Unit) {
        if (capturing) return
        capturing = true
        Thread({
            try {
                status = "capturing…"
                val sock = DatagramSocket().apply { soTimeout = 1000 }
                val addr = InetAddress.getByName("127.0.0.1")
                val dir = File(ctx.getExternalFilesDir(null), "snapshots").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val safe = label.replace(Regex("[^A-Za-z0-9-]+"), "_").take(24)
                val out = File(dir, "snap-$stamp${if (safe.isNotEmpty()) "-$safe" else ""}.bin")
                out.outputStream().buffered().use { os ->
                    os.write("NUZSNAP1".toByteArray())
                    var total = 0; var got = 0
                    REGIONS.forEach { (_, len) -> total += len }
                    for ((base, len) in REGIONS) {
                        os.write(byteArrayOf(
                            (base and 0xFF).toByte(), ((base shr 8) and 0xFF).toByte(),
                            ((base shr 16) and 0xFF).toByte(), ((base shr 24) and 0xFF).toByte()))
                        os.write(byteArrayOf(
                            (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(),
                            ((len shr 16) and 0xFF).toByte(), ((len shr 24) and 0xFF).toByte()))
                        val CH = 1024
                        var o = 0
                        while (o < len) {
                            val want = minOf(CH, len - o)
                            val chunk = readMem(sock, addr, base + o, want)
                            os.write(chunk ?: ByteArray(want))   // zero-fill on miss, keep offsets
                            o += want; got += want
                            if ((o % 16384) == 0) status = "capturing… ${got * 100 / total}%"
                        }
                    }
                }
                sock.close()
                // prune to the newest KEEP
                dir.listFiles { f -> f.name.startsWith("snap-") }
                    ?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
                status = "saved ${out.name} (${out.length() / 1024}KB)"
            } catch (e: Throwable) {
                status = "failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                capturing = false
                onDone()
            }
        }, "nuz-snapshot").start()
    }

    private fun readMem(sock: DatagramSocket, addr: InetAddress, at: Long, len: Int): ByteArray? {
        val cmd = "READ_CORE_MEMORY ${at.toString(16)} $len".toByteArray()
        sock.send(DatagramPacket(cmd, cmd.size, addr, 55355))
        val buf = ByteArray(16 + len * 3 + 64)
        val pkt = DatagramPacket(buf, buf.size)
        return try {
            sock.receive(pkt)
            val parts = String(pkt.data, 0, pkt.length).trim().split(" ")
            if (parts.size < 3 || parts[2] == "-1") null
            else ByteArray(parts.size - 2) { i -> parts[i + 2].toInt(16).toByte() }
        } catch (e: Exception) { null }
    }
}
