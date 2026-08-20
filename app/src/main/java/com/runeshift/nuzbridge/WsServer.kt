package com.runeshift.nuzbridge

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress

/**
 * Loopback-only WebSocket server speaking the NUZ_LIVE protocol.
 * The tracker PWA (https://poke.runeshift.xyz) connects here — verified OK:
 * localhost ws:// is exempt from mixed-content blocking.
 */
// Binds to LOOPBACK by default. "0.0.0.0" is opt-in from Settings and exists so a
// desktop can drive a live memory probe over Tailscale — see MemoryRelay. It is
// off unless deliberately enabled, because binding wider exposes the bridge on
// whatever network the device happens to join.
class WsServer(port: Int, host: String = "127.0.0.1", private val currentState: () -> JSONObject?) :
    WebSocketServer(InetSocketAddress(host, port)) {

    init {
        isReuseAddr = true
        connectionLostTimeout = 30
    }

    @Volatile var clientCount = 0
        private set

    override fun onStart() {}

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        clientCount = connections.size
        val hello = JSONObject()
        hello.put("type", "hello")
        hello.put("source", "ocr")
        hello.put("game", BridgeCore.gameKey)
        conn.send(hello.toString())
        // A tracker connecting after detection has already run would otherwise
        // never hear about it and sit on the wrong game until the next sweep.
        // Send this even when nothing was recognised. A null game used to mean NO frame at
        // all, so an unknown cartridge was indistinguishable from detection never having
        // run: the producer quietly kept decoding the previous game's profile, emitted an
        // empty party, and the tracker had nothing to switch to and nothing to report.
        // The content and crc32 go with it so an unmatched ROM can actually be identified
        // — that is the one piece of information needed to add it to the fingerprints.
        if (GameDetect.lastGame != null || GameDetect.lastContent != null) {
            val d = JSONObject()
            d.put("type", "detect")
            d.put("game", GameDetect.lastGame)
            d.put("content", GameDetect.lastContent)
            d.put("crc32", GameDetect.lastCrc)
            conn.send(d.toString())
        }
        currentState()?.let { conn.send(it.toString()) }
        BridgeCore.notifyChanged()
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        clientCount = connections.size
        BridgeCore.notifyChanged()
    }

    // Inbound: the tracker pushes {type:'config', game, locations, species}
    // when it connects and whenever its selected game changes — the bridge
    // reconfigures itself without touching the app. Anything else is ignored.
    override fun onMessage(conn: WebSocket, message: String) {
        runCatching {
            val o = JSONObject(message)
            // Probe: read emulator memory and hand it back. Only answered when
            // network exposure is on — otherwise the only client is this device.
            if (o.optString("type") == "probe") {
                val addr = o.optString("addr").removePrefix("0x").toLongOrNull(16)
                val len = o.optInt("len", 0)
                val reply = JSONObject()
                reply.put("type", "probe")
                reply.put("addr", o.optString("addr"))
                reply.put("id", o.opt("id"))
                if (addr == null || len <= 0) reply.put("error", "bad addr/len")
                else {
                    val bytes = MemoryRelay.read(addr, len)
                    if (bytes == null) reply.put("error", "RetroArch did not answer")
                    else reply.put("hex", MemoryRelay.toHex(bytes))
                }
                conn.send(reply.toString())
                return
            }
            if (o.optString("type") != "config") return
            val game = o.optString("game")
            val locs = o.optJSONArray("locations")?.toStringList() ?: return
            val species = o.optJSONArray("species")?.toStringList() ?: return
            BridgeCore.applyRemoteProfile(game, locs, species)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {}

    /**
     * Tell the tracker which cartridge is actually running. The tracker follows
     * this by switching to that game's run — the bridge cannot know which RUN
     * the player wants, only which GAME, so the choice among runs stays there.
     */
    fun announceGame(game: String?, content: String?) {
        val o = JSONObject()
        o.put("type", "detect")
        // NULL is a real answer and has to travel. "Something is loaded and I do not know
        // what" is exactly when the tracker must stop trusting the last cartridge it heard
        // about; withholding it left the app naming the previous game until the socket
        // happened to reconnect.
        o.put("game", game ?: JSONObject.NULL)
        o.put("content", content ?: JSONObject.NULL)
        o.put("ts", System.currentTimeMillis())
        runCatching { broadcast(o.toString()) }
    }

    fun broadcastState(state: JSONObject) {
        runCatching { broadcast(state.toString()) }
    }
}
