package com.runeshift.nuzbridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * decodeMon against REAL bytes captured live from RetroArch+mGBA running
 * Pokemon Unbound on 2026-08-17 (READ_CORE_MEMORY 02024284 100) — the
 * player's starter Gible, Lv 10, 34/34 HP, CFRU unencrypted layout.
 */
class MemoryProducerTest {

    private val gibleRaw = (
        "3D 8D 71 6E 7D 52 17 14 BB DB DB DC DC DC DC DC " +
        "DC DC 02 02 BB D5 FF FF FF FF FF 00 00 00 00 00 " +
        "F0 01 2C 00 30 02 00 00 00 32 03 00 21 00 1C 00 " +
        "E8 00 00 00 23 0F 23 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 85 0A 02 BF 98 44 30 00 00 00 00 " +
        "00 00 00 00 0A FF 22 00 22 00 11 00 0E 00 0F 00 " +
        "0D 00 10 00"
    ).trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private fun producer(): MemoryProducer {
        val profile = JSONObject()
        profile.put("game", "unbound")
        profile.put("addrs", JSONObject()
            .put("partyCount", 0x02024029L).put("playerParty", 0x02024284L)
            .put("enemyParty", 0x0202402CL).put("gMapHeader", 0x02036DFCL)
            .put("sectionIdOff", 0x14).put("inBattle", 0x03003529L))
        profile.put("speciesById", JSONObject().put("496", "Gible").put("504", "Skorupi"))
        profile.put("growthById", JSONObject().put("496", 3))
        profile.put("movesById", JSONObject().put("33", "Tackle").put("28", "Sand Attack").put("232", "Metal Claw"))
        profile.put("itemsById", JSONObject().put("44", "Berry Juice"))
        profile.put("sectionNames", JSONObject().put("0", "Frozen Heights").put("13", "Route 1"))
        return MemoryProducer(MemoryProfile(profile))
    }

    @Test fun decodesLiveCapturedGible() {
        val m = producer().decodeMon(gibleRaw)!!
        assertEquals("Gible", m.optString("species"))
        assertEquals(10, m.optInt("level"))
        assertEquals(34, m.optInt("hp"))
        assertEquals(34, m.optInt("maxHp"))
        assertEquals("", m.optString("status"))
    }

    @Test fun expNatureAndPctFromLiveGible() {
        val m = producer().decodeMon(gibleRaw)!!
        // personality 0x6E718D3D % 25 = nature index; exp 560 at Lv10 Medium
        // Slow is EXACTLY the level threshold -> 0% into the level.
        assertEquals(0, m.optInt("expPct", -1))
        assertEquals(MemoryProducer.NATURES[(0x6E718D3DL % 25).toInt()], m.optString("nature"))
    }

    @Test fun heldItemFromLiveGible() {
        // Growth u16 @+34 = 0x002C = 44 = Berry Juice (Unbound gives it to starters).
        assertEquals("Berry Juice", producer().decodeMon(gibleRaw)!!.optString("item"))
    }

    @Test fun movesetFromLiveGible() {
        // The captured Gible's Attacks block reads Tackle/Sand Attack/Metal
        // Claw — the starter's exact in-game moveset.
        val m = producer().decodeMon(gibleRaw)!!
        val moves = m.getJSONArray("moves")
        val list = (0 until moves.length()).map { moves.getString(it) }
        assertEquals(listOf("Tackle", "Sand Attack", "Metal Claw"), list)
    }

    @Test fun evsAndIvsFromLiveGible() {
        // Fresh starter: EVs all zero; IV word 0x304498BF decodes (5-bit
        // fields, game order hp/atk/def/SPE/spa/spd) to 31,5,6,9,4,24 →
        // tracker order (hp,atk,def,spa,spd,SPE) = 31,5,6,4,24,9.
        val m = producer().decodeMon(gibleRaw)!!
        val evs = m.getJSONArray("evs"); val ivs = m.getJSONArray("ivs")
        assertEquals(listOf(0, 0, 0, 0, 0, 0), (0 until 6).map { evs.getInt(it) })
        assertEquals(listOf(31, 5, 6, 4, 24, 9), (0 until 6).map { ivs.getInt(it) })
    }

    @Test fun gameStatsMirrorThePartyStruct() {
        // The cartridge's own stat block (+88..98, game order hp/atk/def/SPE/
        // spa/spd) is re-emitted in tracker order (hp,atk,def,spa,spd,SPE) so
        // the app can DISPLAY the summary-screen numbers rather than
        // recomputing them. maxHp and stats[0] are the same value by
        // construction — that identity is the cheap guard against a
        // reordering regression.
        val m = producer().decodeMon(gibleRaw)!!
        val st = m.getJSONArray("stats")
        assertEquals(6, st.length())
        assertEquals(m.getInt("maxHp"), st.getInt(0))
        val raw = { off: Int -> (gibleRaw[off].toInt() and 0xFF) or ((gibleRaw[off + 1].toInt() and 0xFF) shl 8) }
        assertEquals(listOf(raw(88), raw(90), raw(92), raw(96), raw(98), raw(94)),
                     (0 until 6).map { st.getInt(it) })
    }

    @Test fun battleKindFromRealThorValues() {
        // Every value here was read out of an actual snapshot from the device.
        val wild = MemoryProducer.battleKind(0x4L)
        assertEquals(true, wild.known); assertEquals(false, wild.trainer); assertEquals(false, wild.double)

        val trainerSingle = MemoryProducer.battleKind(0xcL)
        assertEquals(true, trainerSingle.known); assertEquals(true, trainerSingle.trainer)
        assertEquals(false, trainerSingle.double)

        // The regression: CFRU sets 0x200000 on top, and a 16-bit sanity gate
        // threw this away and called a trainer double battle a wild single.
        val trainerDouble = MemoryProducer.battleKind(0x20000dL)
        assertEquals(true, trainerDouble.known); assertEquals(true, trainerDouble.trainer)
        assertEquals(true, trainerDouble.double)

        // Unmapped / garbage reads must stay "unknown" so we never mislabel.
        assertEquals(false, MemoryProducer.battleKind(0x0L).known)
        assertEquals(false, MemoryProducer.battleKind(0xFFFFFFFFL).known)
    }

    @Test fun nicknameDecodesFromPartyStruct() {
        // Nickname is 10 bytes at +8. This fixture was captured before the mon
        // was named and carries filler there, so the useful assertion is that
        // the field decodes at all and lands on the fixture's actual bytes.
        // Validated separately against device snapshots, where the same offset
        // yields the real party: Toot / Chomp / TP / Blubbs / Snowball.
        val m = producer().decodeMon(gibleRaw)!!
        assertEquals("Agghhhhhhh", m.optString("nickname"))
    }

    @Test fun decodeG3HandlesTheRealCharacterRanges() {
        // 0xBB.. upper, 0xD5.. lower, 0xA1.. digits, 0x00 space — "Ab 1".
        val b = byteArrayOf(0xBB.toByte(), 0xD6.toByte(), 0x00, 0xA2.toByte(), 0xFF.toByte())
        assertEquals("Ab 1", MemoryProducer.decodeG3(b, 0, 5))
    }

    @Test fun decodeG3RejectsGarbage() {
        // A byte outside the table means we are not looking at text — return
        // null rather than emitting nonsense into the roster.
        assertNull(MemoryProducer.decodeG3(byteArrayOf(0x50, 0x51, 0x52.toByte()), 0, 3))
        assertEquals("", MemoryProducer.decodeG3(byteArrayOf(0xFF.toByte()), 0, 1))
    }

    @Test fun growthCurveSpotChecks() {
        assertEquals(1000, MemoryProducer.expAt(0, 10))      // Medium Fast n^3
        assertEquals(560, MemoryProducer.expAt(3, 10))       // Medium Slow
        assertEquals(742, MemoryProducer.expAt(3, 11))
        assertEquals(1250, MemoryProducer.expAt(5, 10))      // Slow 5n^3/4
        assertEquals(800, MemoryProducer.expAt(4, 10))       // Fast 4n^3/5
    }

    @Test fun emptySlotDecodesToNull() {
        assertNull(producer().decodeMon(ByteArray(100)))
        assertNull(producer().decodeMon(null))
    }

    @Test fun unknownSpeciesIdRendersPlaceholder() {
        val raw = gibleRaw.copyOf()
        raw[32] = 0x39; raw[33] = 0x05   // species 1337 — not in the test profile
        assertEquals("#1337", producer().decodeMon(raw)!!.optString("species"))
    }
}
