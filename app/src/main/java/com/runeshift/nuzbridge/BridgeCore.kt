package com.runeshift.nuzbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide singleton shared by the activity and the accessibility service.
 * Holds config, the token lists for the selected game, the last emitted state,
 * and the WebSocket server. Everything the UI shows comes from here.
 */
object BridgeCore {
    const val WS_PORT = 8765
    const val SCAN_INTERVAL_MS = 1600L   // OS rate-limits takeScreenshot to ~1/s
    const val ENCOUNTER_CLEAR_SCANS = 4  // battle NOT sustained this many scans → encounter over

    @Volatile var scanning = false
    @Volatile var displayId = 0
    @Volatile var gameKey = "unbound"
    @Volatile var profile: GameProfile? = null
    // Set by the service (and activity) so remote-profile persistence works
    // from the WebSocket thread, which has no Context of its own.
    @Volatile var appContext: Context? = null

    // Observability for the UI.
    @Volatile var lastOcrSummary: String = "—"
    @Volatile var lastEmit: String = "—"
    @Volatile var serviceConnected = false
    @Volatile var scansDone = 0L
    // Set on every successful memory poll cycle: while fresh (<5s), the OCR
    // scanner auto-defers — two producers must never fight over the route.
    @Volatile var lastMemoryPollAt = 0L
    // Separate from lastMemoryPollAt on purpose. That one means "data is fresh" and only a
    // completed poll advances it, so a DELIBERATE pause (wrong profile loaded) freezes it
    // exactly like a dead thread does -- and the watchdog cannot tell "holding on purpose"
    // from "died". This one means "the thread is alive", advanced every iteration
    // including paused ones. Liveness and freshness are different questions.
    @Volatile var lastProducerTickAt = 0L
    // v0.2 diagnostics: every screenshot attempt is accounted for, failures
    // carry the platform error name, and the raw OCR lines are shown so a
    // wrong-display or font-misread problem is visible at a glance.
    @Volatile var scanAttempts = 0L
    @Volatile var scanFailures = 0L
    @Volatile var lastFailure: String = "—"
    @Volatile var rawLines: List<String> = emptyList()

    var server: WsServer? = null
    // High-fidelity producer: RetroArch RAM reads (exact species/HP/route).
    // Runs alongside or instead of OCR; the tracker treats both identically.
    @Volatile var memoryProducer: MemoryProducer? = null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    // Which game the RUNNING producer was built from. Without this the producer
    // silently outlives a game switch: startMemoryProducer bailed out on
    // "already running", so selecting Gaia in the tracker left the Unbound
    // profile decoding Gaia's RAM — wrong species ids, wrong section names, and
    // a bridge that reported "unbound" while the cart was something else.
    @Volatile var memoryGameKey: String? = null

    fun startMemoryProducer(ctx: Context): String? {
        val want = gameKey
        if (memoryProducer?.running == true && memoryGameKey == want) return null
        // Build the NEW profile before tearing down the old one. Stopping first
        // meant any unloadable key killed a perfectly good producer: the tracker
        // fell back to its default game after a run was deleted, pushed
        // 'shining_pearl', and the bridge stopped dead until the memory switch
        // was toggled by hand. A game we cannot read is a reason to keep
        // reading the old one, not to stop reading anything.
        val next = try {
            MemoryProfile(JSONObject(
                ctx.assets.open("memory/$want.json").bufferedReader().use { it.readText() }))
        } catch (e: Exception) {
            return "no memory profile for '$want' (${e.message ?: e.javaClass.simpleName})" +
                (memoryGameKey?.let { " — still reading $it" } ?: "")
        }
        stopMemoryProducer()
        return try {
            val p = MemoryProducer(next)
            memoryProducer = p
            memoryGameKey = want
            p.start()
            ensureServer()
            null
        } catch (e: Exception) {
            memoryGameKey = null
            "could not start producer for '$want' (${e.message ?: e.javaClass.simpleName})"
        }
    }

    fun stopMemoryProducer() {
        memoryProducer?.stop()
        memoryProducer = null
        memoryGameKey = null
        notifyChanged()
    }

    /**
     * RetroArch is running a different game than the one loaded. Swap to it and
     * tell the tracker, so the two ends agree without the user setting the game
     * in two places (and getting it wrong in one, which fills a run with another
     * cartridge's Pokemon).
     *
     * The cartridge is the source of truth here, not either UI: it is the one
     * thing that cannot be mis-selected.
     */
    /** Tell the tracker what detection currently says, without touching the profile. */
    fun announceDetection() {
        server?.announceGame(GameDetect.lastGame, GameDetect.lastContent)
        notifyChanged()
    }

    fun onGameDetected(detected: String) {
        if (detected == gameKey && memoryGameKey == detected) return
        val ctx = appContext ?: return
        gameKey = detected
        ctx.getSharedPreferences("nuzbridge", Context.MODE_PRIVATE)
            .edit().putString("game", detected).apply()
        // Drop a remote OCR profile belonging to the OLD game — its location and
        // species token lists are now wrong, and the tracker will push fresh
        // ones as soon as it follows us across.
        if (profile?.key != detected) profile = GameProfile(detected, emptyList(), emptyList())
        val err = startMemoryProducer(ctx)
        if (err != null) lastFailure = err
        server?.announceGame(detected, GameDetect.lastContent)
        notifyChanged()
    }

    /**
     * Re-arm the producer against the current gameKey. Called after the tracker
     * pushes a new game: a running producer must be rebuilt, not left alone.
     */
    private fun reloadMemoryProducerForGame() {
        if (memoryProducer?.running != true || memoryGameKey == gameKey) return
        val ctx = appContext ?: return
        startMemoryProducer(ctx)?.let { lastFailure = it }
    }

    /**
     * Restart the memory producer if it has gone quiet.
     *
     * `running` alone is not liveness -- it is a flag the producer sets about itself, and a
     * thread that died with it still true reports perfect health forever. The heartbeat is
     * lastMemoryPollAt, which only a completed poll advances, so silence is the honest
     * test. A deliberate wrong-profile pause is NOT silence in that sense: it keeps
     * looping and holds a state we want held.
     *
     * Cheap and idempotent -- call it from anywhere that notices the feed is quiet.
     */
    fun ensureMemoryAlive(ctx: Context, quietMs: Long = 8000L): Boolean {
        val p = memoryProducer ?: return false
        val now = System.currentTimeMillis()
        // A thread that is still ticking needs no restart, whatever it is doing. If it is
        // paused on a wrong profile that is a state to HOLD, and restarting would just
        // rebuild the same profile and pause again.
        if (lastProducerTickAt != 0L && now - lastProducerTickAt < quietMs) return false
        if (lastProducerTickAt == 0L && lastMemoryPollAt == 0L) return false   // still starting
        val quiet = now - maxOf(lastProducerTickAt, lastMemoryPollAt)
        // Capture the cause BEFORE tearing the producer down. stopMemoryProducer() nulls
        // it, and the restart message then overwrote lastFailure -- so the one field that
        // said WHY the thread stopped was destroyed by the recovery. First recurrence
        // reported "silent for 25s, restarted" and nothing else, which is the diagnosis
        // missing exactly the part that matters.
        val cause = p.lastError.takeIf { it.isNotBlank() && it != "-" && it != "—" }
        stopMemoryProducer()
        val err = startMemoryProducer(ctx)
        lastFailure = err ?: ("memory producer stopped after ${quiet / 1000}s - restarted" +
            (cause?.let { " (last error: $it)" } ?: " (no error recorded)"))
        notifyChanged()
        return true
    }

    fun addListener(fn: () -> Unit) { listeners.add(fn) }
    fun removeListener(fn: () -> Unit) { listeners.remove(fn) }
    fun notifyChanged() { for (l in listeners) runCatching { l() } }

    fun loadProfile(ctx: Context, key: String): GameProfile {
        // OCR token lists are optional: a memory-bridge game needs no games/
        // asset, and refusing to select one because that file is missing is
        // what stranded every hack but Unbound on the old dropdown. The tracker
        // pushes real token lists over the socket anyway.
        val o = runCatching {
            JSONObject(ctx.assets.open("games/$key.json").bufferedReader().use { it.readText() })
        }.getOrNull()
        // Never downgrade a richer profile we already hold for this same game:
        // the spinner's initial selection fires asynchronously and would
        // otherwise blank out the token lists the tracker just pushed.
        val keep = profile?.takeIf { it.key == key && it.locations.isNotEmpty() }
        val p = keep ?: GameProfile(key,
            o?.optJSONArray("locations")?.toStringList() ?: emptyList(),
            o?.optJSONArray("species")?.toStringList() ?: emptyList())
        profile = p
        gameKey = key
        reloadMemoryProducerForGame()
        return p
    }

    /** Everything the bridge can read: OCR token lists OR a memory profile. */
    fun listGames(ctx: Context): List<String> =
        listOf("games", "memory")
            .flatMap { (ctx.assets.list(it) ?: emptyArray()).toList() }
            .filter { it.endsWith(".json") && !it.startsWith("_") }   // _fingerprints.json is not a game
            .map { it.removeSuffix(".json") }
            .distinct()
            .sorted()

    /**
     * Profile pushed by the tracker over the WebSocket ({type:'config'}).
     * The tracker knows the selected game AND its location list, so switching
     * games there reconfigures the bridge automatically — no per-game asset
     * needed. Persisted so a background service restart keeps it.
     */
    fun applyRemoteProfile(game: String, locations: List<String>, species: List<String>) {
        if (game.isBlank() || locations.isEmpty() || species.isEmpty()) return
        // The cartridge outranks the tracker. A run deleted in the app drops it
        // to its default game and it pushes that — which used to drag the bridge
        // onto a game it has no profile for while RetroArch sat there plainly
        // running Gaia. Detection is evidence; a config push is a preference.
        val detected = GameDetect.lastGame
        if (detected != null && game != detected) {
            lastFailure = "Ignoring tracker's '$game' — RetroArch is running $detected"
            server?.announceGame(detected, GameDetect.lastContent)
            return
        }
        if (game == profile?.key && locations.size == profile?.locations?.size) {
            // Same OCR profile, but the producer can still be stale — a restored
            // remote profile sets gameKey without touching the memory producer,
            // and the tracker re-sends this identical config on every reconnect.
            reloadMemoryProducerForGame()
            return
        }
        profile = GameProfile(game, locations, species)
        gameKey = game
        appContext?.let { ctx ->
            runCatching {
                val o = JSONObject()
                o.put("game", game)
                o.put("locations", org.json.JSONArray(locations))
                o.put("species", org.json.JSONArray(species))
                ctx.getSharedPreferences("nuzbridge", Context.MODE_PRIVATE)
                    .edit().putString("remoteProfile", o.toString()).putString("game", game).apply()
            }
        }
        reloadMemoryProducerForGame()
        notifyChanged()
    }

    /** Restore a previously pushed remote profile; false if none stored. */
    fun loadRemoteProfile(ctx: Context): Boolean {
        val raw = ctx.getSharedPreferences("nuzbridge", Context.MODE_PRIVATE)
            .getString("remoteProfile", null) ?: return false
        return runCatching {
            val o = JSONObject(raw)
            profile = GameProfile(
                o.getString("game"),
                o.getJSONArray("locations").toStringList(),
                o.getJSONArray("species").toStringList()
            )
            gameKey = o.getString("game")
            reloadMemoryProducerForGame()
            true
        }.getOrDefault(false)
    }

    // Off by default. When on, the WebSocket server binds every interface so a
    // desktop can drive a live memory probe (and, later, so another device can
    // act as the Play-mode screen). Persisted, and surfaced in the UI, because
    // it is the difference between loopback-only and reachable by anything on
    // whatever network this device joins.
    @Volatile var networkExposed = false

    fun setNetworkExposed(ctx: Context, on: Boolean) {
        val want = if (on) "0.0.0.0" else "127.0.0.1"
        // Compare against where the socket ACTUALLY is. Returning early on the
        // flag alone left no way to fix a server that had already bound to the
        // wrong address before the flag was restored.
        if (networkExposed == on && boundHost == want) return
        networkExposed = on
        ctx.getSharedPreferences("nuzbridge", Context.MODE_PRIVATE)
            .edit().putBoolean("netExposed", on).apply()
        // Rebind: the bind address is fixed at construction, so the socket has
        // to be torn down and recreated. stop() releases asynchronously, and
        // rebinding the same port immediately can lose that race and fail - so
        // do it off the UI thread with a moment in between.
        Thread {
            stopServer()
            Thread.sleep(400)
            ensureServer()
            notifyChanged()
        }.start()
    }

    /** Where the socket is actually bound, for the UI to show. */
    @Volatile var boundHost: String = "-"

    fun ensureServer() {
        if (server != null) return
        val host = if (networkExposed) "0.0.0.0" else "127.0.0.1"
        // A failed bind used to be silent: the toggle flipped, nothing listened,
        // and the UI still looked healthy. Record it instead.
        try {
            val s = WsServer(WS_PORT, host) { lastState }
            s.start()
            server = s
            boundHost = host
            if (lastFailure.startsWith("bind")) lastFailure = "-"
        } catch (e: Exception) {
            server = null
            boundHost = "-"
            lastFailure = "bind " + host + ":" + WS_PORT + " failed - " + (e.message ?: e.javaClass.simpleName)
        }
        notifyChanged()
    }

    fun stopServer() {
        runCatching { server?.stop(500) }
        server = null
    }

    // ── State emission ───────────────────────────────────────────────────────
    // Protocol per PLAY_MODE_HANDOFF.md §2. OCR only ever emits route +
    // encounter (never party/hp — not reliable enough to be safe).
    @Volatile var lastState: JSONObject? = null
    private var lastPayload: String? = null

    fun emit(route: String?, encounter: JSONObject?, allies: List<String> = emptyList(), alliesRaw: List<String> = emptyList()) {
        val o = JSONObject()
        o.put("type", "state")
        o.put("source", "ocr")
        o.put("game", gameKey)
        if (route != null) o.put("route", route)
        // JSONObject.NULL is meaningful here: "encounter": null clears the
        // banner in the tracker, absent key means "no change".
        o.put("encounter", encounter ?: JSONObject.NULL)
        // Which of the player's own mons are on the field (OCR of the
        // bottom-half boxes) — lets the tracker preselect whose moves to show.
        o.put("allies", org.json.JSONArray(allies))
        // Canon-folded raw bottom-half text — the tracker matches NICKNAMES
        // against this (nuzlocke mons rarely show their species name).
        o.put("alliesRaw", org.json.JSONArray(alliesRaw))
        o.put("ts", System.currentTimeMillis())
        val s = o.toString()
        if (s.replace(Regex("\"ts\":\\d+"), "") == lastPayload) return  // dedupe ignoring ts
        lastPayload = s.replace(Regex("\"ts\":\\d+"), "")
        lastState = o
        lastEmit = s
        server?.broadcastState(o)
        notifyChanged()
    }
}

data class GameProfile(val key: String, val locations: List<String>, val species: List<String>)

internal fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }
