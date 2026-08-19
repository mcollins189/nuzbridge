package com.runeshift.nuzbridge

import org.json.JSONObject

/** One OCR line with its vertical position, 0.0 (top) .. 1.0 (bottom). */
data class OcrLine(val text: String, val y: Float)

/**
 * Pure text → game-state parser. No Android types so the logic is trivially
 * testable on the desktop. Fed the OCR lines (with vertical position) each
 * scan; keeps a little history for the encounter clear-out hysteresis.
 *
 * Matching philosophy (mirrors NUZ_LIVE.resolveLocation on the tracker side):
 * canon() both sides — uppercase, strip punctuation, and fold the pixel-font
 * confusables ML Kit actually produces (field report: LARVITAR→"LARUITAR",
 * Lv19→"LYIG") — then compare. The tracker fuzzy-matches the route again on
 * receipt, so "close" is enough.
 *
 * Geometry rules (doubles support): enemy info boxes render in the TOP half
 * of the battle screen, the player's in the bottom half. Species named in
 * bottom-half lines are the player's own mons and are never enemy candidates.
 * The one exception is the intro message box ("Wild X and Y appeared!") which
 * sits at the bottom but explicitly names enemies — it always wins.
 */
class OcrParser(profile: GameProfile) {

    // canonical → display. Longest-first ordering so VANILLITE can't be
    // shadowed by a shorter species that happens to be its substring (MEW vs
    // MEWTWO), and 4+ chars only for the *contains* pass (short names like MEW
    // must match as exact tokens or they'd fire on every MEWTWO line).
    private val speciesByNorm = LinkedHashMap<String, String>()
    private val speciesNormsLongFirst: List<String>
    private val locByNorm = LinkedHashMap<String, String>()
    // Last-resort "skeleton" index: ident() + M→N, W→U, O→U, runs collapsed.
    // Catches compound garbles like SNORUNT→"SNIORUMMT" (inserted I + N→MM).
    // Species whose skeletons collide are EXCLUDED — ambiguity means no match.
    private val speciesBySkel: Map<String, String>

    init {
        // Keys use ident() — canon() plus the L→I fold. L↔I is the worst GBA
        // pixel-font confusion (field report: VANILLITE unreadable), but the
        // fold can't live in canon() itself because LEVEL tokens depend on L
        // ("LYIG" = Lv19). So: ident() for all NAME matching, canon() for
        // level parsing.
        for (s in profile.species) {
            val n = ident(s)
            if (n.length >= 3) speciesByNorm.putIfAbsent(n, s)
        }
        speciesNormsLongFirst = speciesByNorm.keys.sortedByDescending { it.length }
        for (l in profile.locations) {
            val n = ident(l)
            if (n.isNotEmpty()) locByNorm.putIfAbsent(n, l)
        }
        val skel = HashMap<String, String?>()
        for ((n, display) in speciesByNorm) {
            val k = skeleton(n)
            if (k.length < 5) continue
            skel[k] = if (skel.containsKey(k) && skel[k] != display) null else display
        }
        speciesBySkel = skel.filterValues { it != null }.mapValues { it.value!! }
    }

    private var scansWithoutBattle = 0
    private var lastAllies: List<String> = emptyList()
    private var lastAlliesRaw: List<String> = emptyList()
    var currentEncounter: JSONObject? = null
        private set
    var currentRoute: String? = null
        private set

    data class Result(
        val route: String?, val encounter: JSONObject?, val changed: Boolean,
        val summary: String, val allies: List<String> = emptyList(),
        val alliesRaw: List<String> = emptyList()
    )

    /** Back-compat / test convenience: no geometry → treat as top-half text. */
    fun feed(rawLines: List<String>): Result = feed(rawLines.map { OcrLine(it, 0.3f) })

    @JvmName("feedLines")
    fun feed(ocrLines: List<OcrLine>): Result {
        val lines = ocrLines.map { OcrLine(it.text.trim(), it.y) }.filter { it.text.isNotEmpty() }
        // Two parallel foldings of every line: identLines (canon + L→I) for
        // ALL name/word matching, normLines (canon only) for LEVEL parsing —
        // level tokens need their L's ("LYIG" = Lv19).
        val normLines = lines.map { canon(it.text) }
        val identLines = lines.map { ident(it.text) }
        val topHalf = lines.indices.filter { lines[it].y < 0.55f }
        var changed = false

        // ── List-screen guard ───────────────────────────────────────────────
        // A location BANNER shows one place; a frame matching 3+ known
        // locations is a menu/list — most likely the tracker's own route
        // picker on the other display (scanning it once poisoned the route
        // with "Starter" in the field). Species version is TOP-HALF only so a
        // doubles battle (2 enemies up top + 2 own mons below) never trips it,
        // while the tracker's encounter table (species everywhere) still does.
        val distinctLocHits = identLines.mapNotNull { locByNorm[it] }.distinct().size
        val topSpeciesHits = topHalf.mapNotNull { findSpecies(identLines[it]) }.distinct()
        val looksLikeList = distinctLocHits >= 3 || topSpeciesHits.size >= 3

        // ── Battle detection ────────────────────────────────────────────────
        // Signals, any of which marks in-battle (all on canon() text so
        // pixel-font misreads still register):
        //  - two battle-menu words (FIGHT/BAG/POKéMON/RUN) — two, so a party
        //    menu's lone "POKEMON" header can't read as a battle
        //  - the wild intro line ("Wild X appeared!")
        //  - "appeared!" alone (intro with "Wild" garbled)
        //  - the action prompt "What will <name> do?"
        val menuHits = BATTLE_MENU_WORDS.count { m -> identLines.any { it == m || it.startsWith(m) && it.length <= m.length + 2 } }
        val wildLine = lines.indices.firstOrNull { identLines[it].contains(W_WILD) && identLines[it].contains(W_APPEARED) }
        val introLine = wildLine ?: lines.indices.firstOrNull { identLines[it].contains(W_APPEARED) }
        val inBattle = menuHits >= 2 || introLine != null || identLines.any { it.contains(W_WHATWILL) }

        // ── Stale-encounter invalidation ────────────────────────────────────
        // A fresh battle INTRO whose text doesn't mention the current enemy
        // means a NEW battle started while the old encounter was still held
        // (chained battles, or the new species was unreadable). Drop the old
        // one — showing nothing for a scan beats showing the wrong Pokémon
        // (field report: panel kept a previous mon during a Vanillite fight).
        if (introLine != null && currentEncounter != null) {
            val curId = ident(currentEncounter!!.optString("species"))
            if (curId.length >= 4 && identLines.none { it.contains(curId) }) {
                currentEncounter = null
                lastAllies = emptyList()
                lastAlliesRaw = emptyList()
                changed = true
            }
        }

        // ── Battle SUSTAIN (fixes panel flicker) ────────────────────────────
        // During move animations the battle menu leaves the screen, so the
        // strict signals go quiet mid-battle and the encounter used to clear
        // after ~5s, then reappear ("fluctuates from showing the info to not").
        // Battle-flavor text (X used MOVE!, It's super effective!, …) and the
        // current enemy's name still being anywhere on screen both count as
        // "battle is still going" — they RESET the clear-out countdown but
        // never START an encounter.
        val curSpecies = currentEncounter?.optString("species")?.let { ident(it) }
        val battleSustained = inBattle ||
            identLines.any { l -> BATTLE_FLAVOR_WORDS.any { l.contains(it) } } ||
            (curSpecies != null && curSpecies.length >= 4 && identLines.any { it.contains(curSpecies) })

        // ── Enemy species (up to 2 — doubles) ───────────────────────────────
        if (inBattle && !looksLikeList) {
            val found = ArrayList<Pair<String, Int?>>(2)   // display name → level
            // The intro line explicitly names the enemies ("Wild X and Y
            // appeared!") — bottom text box, but always authoritative.
            if (introLine != null) {
                for (sp in findAllSpecies(identLines[introLine], 2)) found.add(sp to null)
            }
            // Enemy info boxes: TOP-half lines only, so the player's own mons
            // (bottom half) can never be mistaken for the encounter. Pass 1
            // prefers lines that also carry a level; pass 2 takes any hit.
            for (pass in 0..1) {
                if (found.size >= 2) break
                for (i in topHalf) {
                    if (found.size >= 2) break
                    val lv = levelIn(normLines[i])
                    if (pass == 0 && lv == null) continue
                    val sp = findSpecies(identLines[i]) ?: continue
                    if (found.none { it.first == sp }) found.add(sp to lv)
                }
            }
            // Attach levels: a found-without-level enemy borrows the earliest
            // top-half level token.
            val fallbackLv = topHalf.firstNotNullOfOrNull { levelIn(normLines[it]) }
            if (found.isNotEmpty()) {
                val primary = found[0]
                val partner = found.getOrNull(1)
                val cur = currentEncounter
                val curSpecies = cur?.optString("species")
                val curHasPartner = cur?.has("partner") == true
                // Set when empty; replace if the primary changed; augment if a
                // doubles partner shows up a scan later.
                if (cur == null || curSpecies != primary.first || (partner != null && !curHasPartner)) {
                    val e = JSONObject()
                    e.put("species", primary.first)
                    val plv = primary.second ?: fallbackLv
                    if (plv != null && plv in 1..100) e.put("level", plv)
                    e.put("kind", "wild")
                    if (partner != null) {
                        val po = JSONObject()
                        po.put("species", partner.first)
                        val palv = partner.second
                        if (palv != null && palv in 1..100) po.put("level", palv)
                        e.put("partner", po)
                    }
                    currentEncounter = e
                    changed = true
                }
            }
        }

        // ── Allies (the player's own mons, bottom-half boxes) ───────────────
        // Reported so the tracker can show YOUR active mon's moves scored
        // against the enemy. Battle frames only — in the field, bottom-half
        // species are dialogue/menus, not "who's out".
        val bottomIdx = lines.indices.filter { lines[it].y >= 0.55f }
        val allies = if (battleSustained)
            bottomIdx.mapNotNull { findSpecies(identLines[it]) }.distinct().take(2)
        else lastAllies
        if (battleSustained && allies != lastAllies) { lastAllies = allies; changed = true }
        // Raw (canon-folded) bottom-half text too: NUZLOCKE MONS ARE NICKNAMED,
        // so the in-game box says EMBER, not CYNDAQUIL — species matching can't
        // see it, but the tracker knows the player's nicknames and matches
        // these itself. Short lines only (name boxes), menu words excluded.
        val alliesRaw = if (battleSustained)
            bottomIdx.map { identLines[it] }
                .filter { it.length in 3..20 && BATTLE_MENU_WORDS.none { m -> it == m } }
                .distinct().take(4)
        else lastAlliesRaw
        if (battleSustained && alliesRaw != lastAlliesRaw) { lastAlliesRaw = alliesRaw; changed = true }

        // ── Encounter clear-out (hysteresis) ────────────────────────────────
        if (battleSustained) {
            scansWithoutBattle = 0
        } else if (currentEncounter != null) {
            scansWithoutBattle++
            if (scansWithoutBattle >= BridgeCore.ENCOUNTER_CLEAR_SCANS) {
                currentEncounter = null
                lastAllies = emptyList()
                lastAlliesRaw = emptyList()
                changed = true
            }
        }

        // ── Route (location banner / map header) ────────────────────────────
        // Exact canonical match only, and TOP-of-screen only (y < 0.40): the
        // area-entry banner renders at the top in these games, while signs and
        // NPC dialogue render in the bottom text box — reading a sign that
        // mentions the previous route must not switch you back (field report).
        // Never taken while battle text is on screen, never cleared by
        // unknown text.
        if (!inBattle && !looksLikeList) {
            for (i in lines.indices) {
                if (lines[i].y >= 0.40f) continue
                // Exact first; else one-edit fuzzy ("BIELLIN TOWN"); else
                // bigram similarity for heavily garbled long names ("EILLEN
                // TOWN", "BELLIN TUIN" — the banner font shreds badly). The
                // similarity path needs a clear unique winner and only runs
                // for ≥8-char reads, so Route 1 can never fuzz into Route 2.
                val loc = locByNorm[identLines[i]]
                    ?: fuzzyLookup(identLines[i], locByNorm)
                    ?: similarLocation(identLines[i])
                if (loc != null && loc != currentRoute) {
                    currentRoute = loc
                    changed = true
                    break
                }
            }
        }

        val summary = buildString {
            append(if (inBattle) "[battle] " else "[field] ")
            append(currentRoute ?: "?")
            currentEncounter?.let {
                append(" · wild ").append(it.optString("species"))
                it.optJSONObject("partner")?.let { p -> append(" & ").append(p.optString("species")) }
            }
            append(" · ").append(lines.size).append(" lines")
        }
        return Result(currentRoute, currentEncounter, changed, summary, lastAllies, lastAlliesRaw)
    }

    private fun findSpecies(normLine: String): String? {
        speciesByNorm[normLine]?.let { return it }
        for (n in speciesNormsLongFirst) {
            if (n.length >= 4 && normLine.contains(n)) return speciesByNorm[n]
        }
        // One-edit fuzzy fallback for inserted/dropped characters the canon
        // fold can't fix. Strip the known framing (WILD…APPEARED, level
        // tokens — incl. their ident-folded form, "IYIG", at line end) so the
        // fragment is basically just the name, then require a UNIQUE hit.
        var frag = normLine.replace(W_WILD, "").replace(W_APPEARED, "")
        frag = LV_FUZZY.replace(frag, "")
        frag = IDENT_LV_TAIL.replace(frag, "")
        if (frag.length in 5..20) {
            fuzzyLookup(frag, speciesByNorm)?.let { return it }
            // Skeleton tier for compound garbles (SNORUNT → "SNIORUMMT"):
            // exact skeleton, then unique one-edit among skeletons.
            val sk = skeleton(frag)
            if (sk.length >= 5) {
                speciesBySkel[sk]?.let { return it }
                return fuzzyLookup(sk, speciesBySkel)
            }
        }
        return null
    }

    // Unique bounded-edit-distance lookup: the single map entry within
    // distance 1 of the fragment, or null when none or several qualify
    // (ambiguity = no match; a wrong route/species is worse than none).
    private fun fuzzyLookup(frag: String, map: Map<String, String>): String? {
        if (frag.length < 5) return null
        var hit: String? = null
        for ((k, v) in map) {
            if (kotlin.math.abs(k.length - frag.length) > 1) continue
            if (editDistanceAtMost1(k, frag)) {
                if (hit != null && hit != v) return null   // ambiguous
                hit = v
            }
        }
        return hit
    }

    // Bigram-Dice similarity rescue for long, badly garbled banner reads.
    // Accept only a decisive unique winner: score ≥ 0.5 AND ≥ 0.15 ahead of
    // the runner-up. Guessing wrong is worse than not guessing (field
    // report: it "guessed everything but the right thing" — that was
    // per-scan noise with no margin requirement; this only fires when one
    // location clearly dominates).
    private fun similarLocation(frag: String): String? {
        if (frag.length < 8) return null
        val fb = bigrams(frag)
        if (fb.isEmpty()) return null
        var best: String? = null; var bestScore = 0.0; var second = 0.0
        for ((k, v) in locByNorm) {
            if (k.length < 6) continue
            val kb = bigrams(k)
            val inter = fb.intersect(kb).size
            val score = 2.0 * inter / (fb.size + kb.size)
            if (score > bestScore) { second = bestScore; bestScore = score; best = v }
            else if (score > second) second = score
        }
        return if (best != null && bestScore >= 0.5 && bestScore - second >= 0.15) best else null
    }

    private fun bigrams(s: String): Set<String> =
        (0 until s.length - 1).mapTo(HashSet()) { s.substring(it, it + 2) }

    // All distinct species in one line, longest-first and non-overlapping —
    // "Wild LARVITAR and SNORUNT appeared!" yields both. Matched spans are
    // blanked so MEW can't re-fire inside an already-claimed MEWTWO.
    private fun findAllSpecies(normLine: String, max: Int): List<String> {
        val out = ArrayList<String>(max)
        var hay = normLine
        speciesByNorm[hay]?.let { return listOf(it) }
        for (n in speciesNormsLongFirst) {
            if (out.size >= max) break
            if (n.length < 4) continue
            val idx = hay.indexOf(n)
            if (idx >= 0) {
                val sp = speciesByNorm[n]!!
                if (sp !in out) out.add(sp)
                hay = hay.substring(0, idx) + "#".repeat(n.length) + hay.substring(idx + n.length)
            }
        }
        return out
    }

    companion object {
        fun norm(s: String): String = s.uppercase().replace(Regex("[^A-Z0-9]"), "")

        // Fold the letter/digit shapes the GBA pixel font makes ML Kit confuse
        // into one canonical character each, applied to BOTH the token lists
        // and the OCR text, so LARUITAR == LARVITAR without any fuzzy search.
        private val CANON_MAP = mapOf(
            'V' to 'U',
            '0' to 'O', '1' to 'I', '2' to 'Z', '3' to 'E', '4' to 'A',
            '5' to 'S', '6' to 'G', '8' to 'B', '9' to 'G'
        )
        fun canon(s: String): String {
            val n = norm(s)
            val sb = StringBuilder(n.length)
            for (c in n) sb.append(CANON_MAP[c] ?: c)
            return sb.toString()
        }

        // canon() + the L→I fold, for NAME/WORD matching only. L, I and 1 are
        // near-identical glyphs in the GBA pixel font (field report: VANILLITE
        // unreadable — its ILLI cluster shreds). Level parsing keeps using
        // canon(), because level tokens are recognized BY their L ("LYIG").
        fun ident(s: String): String = canon(s).replace('L', 'I')

        // Skeleton fold: the remaining confusable pairs (M↔N; W and O both
        // read as U-ish shapes), plus collapsing repeated letters (doubled
        // strokes merge/split freely in this font). Only ever used with a
        // uniqueness requirement — aggressive folding is safe because a
        // collision disqualifies the entry rather than guessing.
        fun skeleton(s: String): String {
            val folded = ident(s).replace('M', 'N').replace('W', 'U').replace('O', 'U')
            val sb = StringBuilder(folded.length)
            for (c in folded) if (sb.isEmpty() || sb.last() != c) sb.append(c)
            return sb.toString()
        }

        // Ident-folded level token at end of a fragment ("LYIG" → "IYIG").
        private val IDENT_LV_TAIL = Regex("[IL][UVY][A-Z0-9]{1,3}$")

        private val BATTLE_MENU_WORDS = listOf("FIGHT", "RUN", "BAG", "POKEMON").map { ident(it) }
        val W_WILD = ident("WILD")
        val W_APPEARED = ident("APPEARED")
        val W_WHATWILL = ident("WHATWILL")
        // Battle-flavor text that keeps a battle "alive" through animations —
        // ident-folded like everything else name-shaped. "USED" alone is too
        // short/common; "GAINED"/"EXP" extend a finished battle a few
        // harmless seconds.
        private val BATTLE_FLAVOR_WORDS = listOf(
            "EFFECTIVE", "CRITICAL", "FAINTED", "SENTOUT", "GAINEDEXP", "EXPPOINTS", "MISSED", "AVOIDED"
        ).map { ident(it) }

        // Level extraction on canon() text. "Lv19" arrives as "LYIG" (v→Y,
        // 1→I, 9→G), so after an LV-ish prefix we accept confusable LETTERS
        // and decode them back to digits; any undecodable char rejects the
        // token (keeps "LIKE"/"SILVER" from minting phantom levels). Bare "L"
        // only counts with REAL digits after it.
        private val LETTER_TO_DIGIT = mapOf(
            'O' to 0, 'I' to 1, 'Z' to 2, 'E' to 3, 'A' to 4,
            'S' to 5, 'B' to 8, 'G' to 9, 'T' to 7,
            '0' to 0, '1' to 1, '2' to 2, '3' to 3, '4' to 4,
            '5' to 5, '6' to 6, '7' to 7, '8' to 8, '9' to 9
        )
        // True when edit distance (insert/delete/substitute) between a and b
        // is ≤1. Single-pass two-pointer — no DP table needed at this bound.
        fun editDistanceAtMost1(a: String, b: String): Boolean {
            if (a == b) return true
            val (s, l) = if (a.length <= b.length) a to b else b to a
            if (l.length - s.length > 1) return false
            var i = 0; var j = 0; var edits = 0
            while (i < s.length && j < l.length) {
                if (s[i] == l[j]) { i++; j++; continue }
                if (++edits > 1) return false
                if (s.length == l.length) { i++; j++ }   // substitution
                else j++                                  // insertion in the longer
            }
            return edits + (l.length - j) + (s.length - i) <= 1
        }

        private val LV_FUZZY = Regex("(?:LV|LY|LU|IV)([A-Z0-9]{1,3})")
        private val LV_STRICT = Regex("L(\\d{1,3})")
        private fun decodeDigits(tok: String): Int? {
            var v = 0
            for (c in tok) { val d = LETTER_TO_DIGIT[c] ?: return null; v = v * 10 + d }
            return v
        }
        fun levelIn(canonLine: String): Int? {
            for (m in LV_FUZZY.findAll(canonLine)) {
                val v = decodeDigits(m.groupValues[1])
                if (v != null && v in 2..100) return v
            }
            for (m in LV_STRICT.findAll(canonLine)) {
                val v = m.groupValues[1].toInt()
                if (v in 2..100) return v
            }
            return null
        }
    }
}
