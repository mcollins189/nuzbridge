package com.runeshift.nuzbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Desktop tests for the OCR→state parser — the piece of the app that can be
 * verified without the handheld. Sample lines mimic real GBA screen text as
 * ML Kit tends to read it (caps, glued level tokens, minor misreads).
 */
class OcrParserTest {

    private fun parser() = OcrParser(
        GameProfile(
            "unbound",
            locations = listOf("Route 1", "Route 2", "Frozen Heights", "Icicle Cave", "Bellin Town"),
            species = listOf("Vanillite", "Snorunt", "Mew", "Mewtwo", "Delibird", "Cyndaquil", "Larvitar")
        )
    )

    @Test fun wildIntroSetsEncounter() {
        val p = parser()
        val r = p.feed(listOf("Wild VANILLITE appeared!"))
        assertEquals("Vanillite", r.encounter?.optString("species"))
        assertEquals("wild", r.encounter?.optString("kind"))
    }

    @Test fun enemyBoxWithLevel() {
        val p = parser()
        // Battle menu markers + enemy info box on one line, as OCR often merges it.
        val r = p.feed(listOf("VANILLITE Lv7", "FIGHT", "BAG", "POKEMON", "RUN"))
        assertEquals("Vanillite", r.encounter?.optString("species"))
        assertEquals(7, r.encounter?.optInt("level"))
    }

    @Test fun mewDoesNotShadowMewtwo() {
        val p = parser()
        val r = p.feed(listOf("Wild MEWTWO appeared!"))
        assertEquals("Mewtwo", r.encounter?.optString("species"))
    }

    @Test fun shortNameNeedsExactToken() {
        val p = parser()
        // "HOMEWORK" contains MEW after normalization; must NOT match.
        val r = p.feed(listOf("Wild HOMEWORK appeared!", "FIGHT", "RUN"))
        assertNull(r.encounter)
        // But an exact MEW enemy box does match.
        val r2 = parser().feed(listOf("MEW", "Lv50", "FIGHT", "BAG", "RUN"))
        assertEquals("Mew", r2.encounter?.optString("species"))
    }

    @Test fun singleMenuWordIsNotABattle() {
        val p = parser()
        // A lone "POKEMON" header (party menu) must not read as in-battle.
        val r = p.feed(listOf("POKEMON", "CYNDAQUIL Lv12"))
        assertNull(r.encounter)
    }

    @Test fun encounterClearsAfterHysteresis() {
        val p = parser()
        p.feed(listOf("Wild SNORUNT appeared!"))
        assertNotNull(p.currentEncounter)
        // Field screens without battle markers: cleared only on the Nth scan.
        p.feed(listOf("ROUTE 1"))
        p.feed(listOf("ROUTE 1"))
        p.feed(listOf("ROUTE 1"))
        assertNotNull(p.currentEncounter)
        val r = p.feed(listOf("ROUTE 1"))
        assertNull(r.encounter)
    }

    @Test fun animationFramesDoNotFlickerEncounter() {
        val p = parser()
        p.feed(listOf("Wild SNORUNT appeared!"))
        // Mid-battle animation frames: no menu, no intro — but flavor text or
        // the enemy's name keep the battle alive indefinitely.
        repeat(6) { p.feed(listOf("SNORUNT used ICY WIND!")) }
        repeat(6) { p.feed(listOf("It's super effectiue!")) }   // OCR'd v→u
        repeat(6) { p.feed(listOf("A critical hit!")) }
        assertNotNull(p.currentEncounter)
        assertEquals("Snorunt", p.currentEncounter?.optString("species"))
    }

    @Test fun alliesReportedFromBottomHalf() {
        val p = parser()
        val r = p.feed(listOf(
            OcrLine("LARUITAR LYIG", 0.10f),
            OcrLine("CYNDAQUIL Lv20", 0.70f),
            OcrLine("FIGHT", 0.9f), OcrLine("RUN", 0.9f)
        ))
        assertEquals(listOf("Cyndaquil"), r.allies)
        assertEquals("Larvitar", r.encounter?.optString("species"))
    }

    @Test fun nicknamedAllyRawTextReported() {
        val p = parser()
        // The player's mon is NICKNAMED — species matching can't see it, but
        // the raw (canon) text must flow through for the tracker to match
        // nicknames itself. Menu words are excluded from the raw list.
        val r = p.feed(listOf(
            OcrLine("LARUITAR LYIG", 0.10f),
            OcrLine("CHOMP Lv14", 0.70f),
            OcrLine("FIGHT", 0.9f), OcrLine("RUN", 0.9f)
        ))
        assertEquals(emptyList<String>(), r.allies)
        assertEquals(listOf(OcrParser.ident("CHOMP Lv14")), r.alliesRaw)
    }

    @Test fun routeBannerMatchesAndSticks() {
        val p = parser()
        assertEquals("Frozen Heights", p.feed(listOf("FROZEN HEIGHTS")).route)
        // Unknown text never clears the route.
        assertEquals("Frozen Heights", p.feed(listOf("some flavor text")).route)
    }

    @Test fun insertedCharacterStillMatchesLocation() {
        val p = parser()
        // Field report: "BELLIN TOWN" OCR'd as "BIELLIN TOWN" (inserted I) —
        // one-edit fuzzy must still switch, from the top banner position.
        assertEquals("Bellin Town", p.feed(listOf(OcrLine("BIELLIN TOWN", 0.08f))).route)
    }

    @Test fun heavilyGarbledBannerStillMatches() {
        // The full set of real misreads reported for "BELLIN TOWN".
        for (garble in listOf("BIELLIN TOWN", "EILLEN TOWN", "BELLIN TUIN", "EI LLEN TOWN")) {
            val p = parser()
            assertEquals(garble, "Bellin Town", p.feed(listOf(OcrLine(garble, 0.08f))).route)
        }
    }

    @Test fun garbageDoesNotMatchAnything() {
        val p = parser()
        p.feed(listOf(OcrLine("FROZEN HEIGHTS", 0.08f)))
        // Long random text in the banner band must not fuzz into a location.
        val r = p.feed(listOf(OcrLine("POKEMART SALE TODAY", 0.08f)))
        assertEquals("Frozen Heights", r.route)
    }

    @Test fun ambiguousFuzzyDoesNotGuess() {
        val p = parser()
        p.feed(listOf(OcrLine("FROZEN HEIGHTS", 0.08f)))
        // "ROUTE S" is one edit from BOTH Route 1 (ROUTEI) and Route 2
        // (ROUTEZ) after folding — ambiguous, must not switch.
        val r = p.feed(listOf(OcrLine("ROUTE S", 0.08f)))
        assertEquals("Frozen Heights", r.route)
        // "ROUTE L" however IS Route 1 — L, I and 1 are the same glyph family.
        assertEquals("Route 1", p.feed(listOf(OcrLine("ROUTE L", 0.08f))).route)
    }

    // ── L↔I folding + stale-encounter invalidation (Vanillite field report) ──

    @Test fun vanilliteMisreadsMatch() {
        for (garble in listOf("VANLLLITE", "VANIILITE", "VANILLLITE", "VAN1LL1TE")) {
            val p = OcrParser(GameProfile("unbound",
                listOf("Route 1"), listOf("Vanillite", "Snorunt", "Vanillish")))
            val r = p.feed(listOf("Wild $garble appeared!"))
            assertEquals(garble, "Vanillite", r.encounter?.optString("species"))
        }
    }

    @Test fun compoundGarbleMatchesViaSkeleton() {
        // Field report: SNORUNT read as "SNIORUMMT" — inserted I AND N→MM.
        val p = parser()
        val r = p.feed(listOf("Wild SNIORUMMT appeared!"))
        assertEquals("Snorunt", r.encounter?.optString("species"))
        // Same class of garble in the enemy info box with a level token.
        val p2 = parser()
        val r2 = p2.feed(listOf(OcrLine("SNIORUMMT LYIG", 0.10f), OcrLine("FIGHT", 0.9f), OcrLine("RUN", 0.9f)))
        assertEquals("Snorunt", r2.encounter?.optString("species"))
    }

    @Test fun staleEncounterDroppedOnNewIntro() {
        val p = parser()
        p.feed(listOf("Wild SNORUNT appeared!"))
        assertEquals("Snorunt", p.currentEncounter?.optString("species"))
        // New battle intro with an UNREADABLE species and no Snorunt on
        // screen: the stale encounter must drop (nothing beats wrong info).
        val r = p.feed(listOf("Wild VAMWTECC appeared!"))
        assertNull(r.encounter)
        // Next scan reads it properly → new encounter.
        assertEquals("Vanillite",
            OcrParser(GameProfile("unbound", listOf("Route 1"), listOf("Vanillite")))
                .feed(listOf("Wild VANILLITE appeared!")).encounter?.optString("species"))
    }

    @Test fun fuzzySpeciesInWildIntro() {
        val p = parser()
        // Inserted character in the intro line: "SNIORUNT" → Snorunt.
        val r = p.feed(listOf("Wild SNIORUNT appeared!"))
        assertEquals("Snorunt", r.encounter?.optString("species"))
    }

    @Test fun signDialogueDoesNotChangeRoute() {
        val p = parser()
        // Entered Route 1 (banner at top of screen)…
        assertEquals("Route 1", p.feed(listOf(OcrLine("ROUTE 1", 0.08f))).route)
        // …then read a sign whose BOTTOM-box text mentions another area.
        val r = p.feed(listOf(OcrLine("ICICLE CAVE", 0.82f)))
        assertEquals("Route 1", r.route)
        // A real banner for that area still switches.
        assertEquals("Icicle Cave", p.feed(listOf(OcrLine("ICICLE CAVE", 0.08f))).route)
    }

    @Test fun routeNotTakenFromBattleText() {
        val p = parser()
        p.feed(listOf("ROUTE 1"))
        // While in battle, location words in flavor text must not change route.
        val r = p.feed(listOf("Wild DELIBIRD appeared!", "ICICLE CAVE"))
        assertEquals("Route 1", r.route)
    }

    @Test fun trackerRoutePickerDoesNotSetRoute() {
        val p = parser()
        // Scanning the tracker's own screen: its route picker lists many
        // locations. A real banner shows ONE — 3+ matches means a list.
        val r = p.feed(listOf("ROUTE 1", "FROZEN HEIGHTS", "ICICLE CAVE", "BELLIN TOWN"))
        assertNull(r.route)
        // A genuine lone banner still works afterwards.
        assertEquals("Route 1", p.feed(listOf("ROUTE 1")).route)
    }

    @Test fun encounterTableDoesNotFakeEncounter() {
        val p = parser()
        // Tracker encounter table on screen: many species + a stray marker
        // pair must not conjure a wild battle.
        val r = p.feed(listOf("FIGHT", "RUN", "Vanillite", "Snorunt", "Delibird", "Cyndaquil"))
        assertNull(r.encounter)
    }

    // ── Doubles battles (2 enemies top-half, own mons bottom-half) ──────────

    @Test fun doublesFindsBothEnemiesIgnoresOwnMons() {
        val p = parser()
        val r = p.feed(listOf(
            OcrLine("LARUITAR LYIG", 0.10f),      // enemy 1 (misread)
            OcrLine("SNORUNT Lv18", 0.15f),       // enemy 2
            OcrLine("CYNDAQUIL Lv20", 0.70f),     // own mon — bottom half
            OcrLine("DELIBIRD Lv21", 0.75f),      // own mon — bottom half
            OcrLine("FIGHT", 0.9f), OcrLine("RUN", 0.9f)
        ))
        assertEquals("Larvitar", r.encounter?.optString("species"))
        assertEquals(19, r.encounter?.optInt("level"))
        val partner = r.encounter?.optJSONObject("partner")
        assertEquals("Snorunt", partner?.optString("species"))
        assertEquals(18, partner?.optInt("level"))
    }

    @Test fun doublesIntroNamesBoth() {
        val p = parser()
        val r = p.feed(listOf(OcrLine("Wild LARUITAR and SNORUNT appeared!", 0.85f)))
        assertEquals("Larvitar", r.encounter?.optString("species"))
        assertEquals("Snorunt", r.encounter?.optJSONObject("partner")?.optString("species"))
    }

    @Test fun partnerFoundLaterAugmentsEncounter() {
        val p = parser()
        p.feed(listOf(OcrLine("LARUITAR LYIG", 0.10f), OcrLine("FIGHT", 0.9f), OcrLine("RUN", 0.9f)))
        assertNull(p.currentEncounter?.optJSONObject("partner"))
        val r = p.feed(listOf(
            OcrLine("LARUITAR LYIG", 0.10f), OcrLine("SNORUNT Lv18", 0.15f),
            OcrLine("FIGHT", 0.9f), OcrLine("RUN", 0.9f)
        ))
        assertEquals("Snorunt", r.encounter?.optJSONObject("partner")?.optString("species"))
    }

    @Test fun ownMonAloneIsNotAnEncounter() {
        val p = parser()
        // Battle screen where only the player's own box got read — no enemy.
        val r = p.feed(listOf(
            OcrLine("CYNDAQUIL Lv20", 0.72f),
            OcrLine("FIGHT", 0.9f), OcrLine("RUN", 0.9f)
        ))
        assertNull(r.encounter)
    }

    @Test fun levelTokenVariants() {
        assertEquals(23, OcrParser.levelIn(OcrParser.canon("Lv23")))
        assertEquals(23, OcrParser.levelIn(OcrParser.canon("Ly 23")))   // OCR misread
        assertEquals(7, OcrParser.levelIn(OcrParser.canon("SNORUNT L7")))
        assertEquals(19, OcrParser.levelIn(OcrParser.canon("LYIG")))    // field report: Lv19
        assertNull(OcrParser.levelIn(OcrParser.canon("no level here")))
        assertNull(OcrParser.levelIn(OcrParser.canon("SILVER LIKE")))   // LV-ish prefixes with undecodable tails
    }

    // ── Field-reported pixel-font misreads (2026-08-17, Unbound on the Thor) ──

    @Test fun larvitarMisreadMatches() {
        val p = parser()
        // ML Kit read "LARVITAR Lv19" as "LARUITAR LYIG"; canon() folds V→U
        // and decodes the level, so this must resolve fully.
        val r = p.feed(listOf("LARUITAR LYIG", "FIGHT", "BAG", "RUN"))
        assertEquals("Larvitar", r.encounter?.optString("species"))
        assertEquals(19, r.encounter?.optInt("level"))
    }

    @Test fun garbledIntroStillBattles() {
        val p = parser()
        // "Wild" garbled but "appeared!" survived → still a battle signal.
        val r = p.feed(listOf("W1ld LARUITAR appeared!"))
        assertEquals("Larvitar", r.encounter?.optString("species"))
    }

    @Test fun whatWillPromptIsABattleSignal() {
        val p = parser()
        val r = p.feed(listOf("What will EMBER do?", "LARUITAR LYIG"))
        assertEquals("Larvitar", r.encounter?.optString("species"))
    }
}
