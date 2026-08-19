package com.runeshift.nuzbridge

import android.content.Context
import org.json.JSONObject

/**
 * Works out which game RetroArch is actually running, so the bridge stops
 * depending on the human having selected the right one in two places.
 *
 * Every memory address in a profile is per-GAME. Point Unbound's profile at
 * Gaia and it does not fail — both are FireRed-based, so the reads land, the
 * species ids resolve to real (wrong) names, and a roster quietly fills with
 * Pokemon from the wrong cartridge. That is what happened, and the existing
 * "profile may not match" heuristic missed it because it only fires on
 * UNRESOLVED species ids, which is the easy case.
 *
 * The authoritative answer is RetroArch's own GET_STATUS reply, which carries
 * the content name and its CRC32. Fingerprints live in assets/memory/
 * _fingerprints.json — see that file for why CRC32 and not the ROM header.
 */
object GameDetect {

    data class Result(val game: String?, val content: String, val crc32: String?)

    /** Last thing RetroArch told us, for the status screen. */
    @Volatile var lastContent: String = "—"
    @Volatile var lastCrc: String? = null
    @Volatile var lastGame: String? = null

    private var table: JSONObject? = null

    private fun table(ctx: Context): JSONObject {
        table?.let { return it }
        val t = runCatching {
            JSONObject(ctx.assets.open("memory/_fingerprints.json").bufferedReader().use { it.readText() })
                .getJSONObject("games")
        }.getOrDefault(JSONObject())
        table = t
        return t
    }

    /**
     * Parse a GET_STATUS reply. RetroArch has shuffled the field order between
     * versions, so nothing here is positional: the CRC is pulled by pattern and
     * the whole payload is treated as the haystack for name matching.
     *
     * Replies look like:
     *   GET_STATUS PLAYING gpsp,Pokemon Gaia,crc32=b9465c5a
     *   GET_STATUS CONTENTLESS
     */
    fun parse(ctx: Context, reply: String): Result {
        val body = reply.trim().removePrefix("GET_STATUS").trim()
        if (body.isEmpty() || body.startsWith("CONTENTLESS") || body.startsWith("NO_CONTENT")) {
            return Result(null, "no content loaded", null)
        }
        val crc = Regex("crc32=([0-9a-fA-F]+)").find(body)?.groupValues?.get(1)
            ?.trimStart('0')?.takeIf { it.isNotEmpty() }?.uppercase()
        // Everything after the status word, minus the crc field, is the content
        // description — good enough to show the user and to name-match against.
        val content = body.substringAfter(' ', body)
            .replace(Regex(",?crc32=[0-9a-fA-F]+"), "").trim().ifEmpty { body }
        return Result(resolve(ctx, crc, content), content, crc)
    }

    /** CRC first (exact); name only as a fallback, and never for strict entries. */
    fun resolve(ctx: Context, crc: String?, content: String): String? {
        val games = table(ctx)
        val keys = games.keys().asSequence().toList()

        if (crc != null) {
            for (k in keys) {
                val arr = games.optJSONObject(k)?.optJSONArray("crc32") ?: continue
                for (i in 0 until arr.length()) {
                    if (arr.getString(i).trimStart('0').equals(crc, ignoreCase = true)) return k
                }
            }
        }

        // No CRC match. Fall back to the content name, but take the LONGEST
        // matching token so "Fire Red Team Rocket Edition" resolves to the hack
        // rather than to FireRed — both tokens are present and the specific one
        // has to win.
        val hay = content.lowercase()
        var best: String? = null
        var bestLen = 0
        for (k in keys) {
            val g = games.optJSONObject(k) ?: continue
            if (g.optBoolean("strict")) continue
            val arr = g.optJSONArray("match") ?: continue
            for (i in 0 until arr.length()) {
                val tok = arr.getString(i).lowercase()
                if (hay.contains(tok) && tok.length > bestLen) { best = k; bestLen = tok.length }
            }
        }
        return best
    }

    /** Record a detection for the UI; returns true if the game changed. */
    fun record(r: Result): Boolean {
        val changed = r.game != lastGame
        lastContent = r.content
        lastCrc = r.crc32
        lastGame = r.game
        return changed
    }

    fun describe(): String = buildString {
        append(lastContent)
        lastCrc?.let { append("  crc32=").append(it) }
        append("  → ").append(lastGame ?: "UNRECOGNISED")
    }
}
