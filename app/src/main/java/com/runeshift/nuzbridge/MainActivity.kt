package com.runeshift.nuzbridge

import android.app.Activity
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.view.View

/**
 * Deliberately spartan control panel. Everything of substance happens in
 * BridgeAccessibilityService + BridgeCore; this just configures and observes.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private val refresh: () -> Unit = { runOnUiThread { renderStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val rootCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(t: String) = rootCol.addView(TextView(this).apply {
            text = t; textSize = 13f; alpha = 0.7f; setPadding(0, pad / 2, 0, 4)
        })

        // ── Game profile ────────────────────────────────────────────────────
        label("Game profile")
        val games = BridgeCore.listGames(this)
        val gameSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, games)
            val idx = games.indexOf(prefs().getString("game", "unbound"))
            if (idx >= 0) setSelection(idx)
            // A tracker-pushed game with no bundled asset left idx -1, the
            // Spinner defaulted to position 0, and Android's async initial
            // onItemSelected then fired for position 0 — silently re-pointing
            // the bridge (and the persisted pref) at the alphabetically-first
            // game. Swallow that one initial callback when the saved game is
            // not in the list.
            var suppressInitial = idx < 0
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (suppressInitial) { suppressInitial = false; return }
                    val key = games[pos]
                    prefs().edit().putString("game", key).apply()
                    runCatching { BridgeCore.loadProfile(this@MainActivity, key) }
                    renderStatus()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        rootCol.addView(gameSpinner)

        // ── Display to scan ─────────────────────────────────────────────────
        label("Screen to scan (the one the GAME is on)")
        val dm = getSystemService(DisplayManager::class.java)
        val displays = dm.displays
        val displayNames = displays.map { "Display ${it.displayId}: ${it.name}" }
        val displaySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, displayNames)
            val saved = prefs().getInt("displayId", 0)
            val idx = displays.indexOfFirst { it.displayId == saved }
            if (idx >= 0) setSelection(idx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    BridgeCore.displayId = displays[pos].displayId
                    prefs().edit().putInt("displayId", BridgeCore.displayId).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        rootCol.addView(displaySpinner)

        // ── Scan toggle ─────────────────────────────────────────────────────
        label("Scanning")
        val toggle = Switch(this).apply {
            text = "Read the game screen"
            isChecked = BridgeCore.scanning
            setOnCheckedChangeListener { _, on ->
                BridgeCore.scanning = on
                // Persisted so the accessibility service resumes scanning
                // after a background process restart without the UI opening.
                prefs().edit().putBoolean("scanning", on).apply()
                renderStatus()
            }
        }
        rootCol.addView(toggle)

        // ── Memory bridge (RetroArch RAM) ───────────────────────────────────
        label("Memory bridge — exact data from RetroArch (Network Commands ON)")
        val memToggle = Switch(this).apply {
            text = "Read RetroArch memory"
            isChecked = BridgeCore.memoryProducer?.running == true
            setOnCheckedChangeListener { _, on ->
                if (on) {
                    val err = BridgeCore.startMemoryProducer(this@MainActivity)
                    if (err != null) { BridgeCore.lastFailure = err; isChecked = false }
                } else BridgeCore.stopMemoryProducer()
                prefs().edit().putBoolean("memory", isChecked).apply()
                renderStatus()
            }
        }
        rootCol.addView(memToggle)

        // ── Network access (probe / second screen) ───────────────────────────
        label("Network access — lets another device reach this bridge. OFF unless needed.")
        val netToggle = Switch(this).apply {
            text = "Allow network access"
            isChecked = BridgeCore.networkExposed
            setOnCheckedChangeListener { _, on ->
                BridgeCore.setNetworkExposed(this@MainActivity, on)
                renderStatus()
            }
        }
        rootCol.addView(netToggle)

        // ── RAM snapshot (offline analysis) ─────────────────────────────────
        // Snapshots are timestamped and the newest 12 are kept, so captures
        // never overwrite each other. The LABEL is what makes a set usable:
        // finding an address means diffing two captures that differ in exactly
        // one thing, and unlabelled files ("manual") leave no record of which
        // was which — which is precisely what stalled the turn-cursor hunt.
        label("RAM snapshot — capture at interesting moments, pull over USB")
        val snapLabel = EditText(this).apply {
            hint = "label this capture, e.g. left-menu / target-right"
            textSize = 14f
            setSingleLine()
        }
        rootCol.addView(snapLabel)
        // One tap per state: pre-fills the label AND captures, so a four-state
        // sequence needs no typing between shots (the game sits still while you
        // are on an action/target menu, so switching apps is safe).
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (preset in listOf("left-menu", "right-menu", "target-a", "target-b")) {
            quick.addView(Button(this).apply {
                text = preset
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    snapLabel.setText(preset)
                    SnapshotTool.capture(this@MainActivity, preset) { runOnUiThread { renderStatus() } }
                    renderStatus()
                }
            })
        }
        rootCol.addView(quick)
        rootCol.addView(Button(this).apply {
            text = "Capture RAM snapshot"
            setOnClickListener {
                val lbl = snapLabel.text.toString().trim().ifEmpty { "manual" }
                SnapshotTool.capture(this@MainActivity, lbl) { runOnUiThread { renderStatus() } }
                renderStatus()
            }
        })

        // ── Accessibility shortcut ──────────────────────────────────────────
        rootCol.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        // ── Status ──────────────────────────────────────────────────────────
        label("Status")
        status = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        rootCol.addView(status)

        setContentView(ScrollView(this).apply { addView(rootCol) })

        // Restore config into the core before the service starts using it.
        BridgeCore.appContext = applicationContext
        BridgeCore.displayId = prefs().getInt("displayId", 0)
        // Restore the network-exposure choice before the server starts, or
        // the first bind would use the default and the switch would show a
        // state the socket does not actually have.
        BridgeCore.networkExposed = prefs().getBoolean("netExposed", false)
        // The accessibility service may already have bound the socket using the
        // default before this ran. Re-apply so the address matches the setting.
        if (BridgeCore.server != null) BridgeCore.setNetworkExposed(this, BridgeCore.networkExposed)
        if (!BridgeCore.loadRemoteProfile(this)) {
            val savedGame = prefs().getString("game", "unbound") ?: "unbound"
            runCatching { BridgeCore.loadProfile(this, savedGame) }
        }
    }

    override fun onResume() {
        super.onResume()
        BridgeCore.addListener(refresh)
        renderStatus()
    }

    override fun onPause() {
        super.onPause()
        BridgeCore.removeListener(refresh)
    }

    private fun renderStatus() {
        val svc = if (BridgeCore.serviceConnected) "connected ✓" else "OFF — enable NuzBridge in Accessibility settings"
        val clients = BridgeCore.server?.clientCount ?: 0
        status.text = buildString {
            // Which build is actually running. Without this, "I don't see the new
            // field" is ambiguous between a bug and an install that didn't take —
            // and that ambiguity has cost a debugging round trip.
            val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
            append("NuzBridge v").append(ver ?: "?").append('\n')
            append("Accessibility service: ").append(svc).append('\n')
            // Show the address actually BOUND, not a hardcoded one. The whole
            // point of the network toggle is that this can differ, and a status
            // line that always claims 127.0.0.1 hides a failed rebind.
            append("WebSocket: ws://").append(BridgeCore.boundHost).append(":").append(BridgeCore.WS_PORT)
                .append("  (").append(clients).append(" client").append(if (clients == 1) "" else "s").append(")")
            if (BridgeCore.networkExposed) append("  [network access ON]")
            append("\n")
            append("Game: ").append(BridgeCore.gameKey)
            BridgeCore.profile?.let { append("  (").append(it.locations.size).append(" locations, ").append(it.species.size).append(" species)") }
            append('\n')
            append("Scanning display: ").append(BridgeCore.displayId)
                .append(if (BridgeCore.scanning) "  (ON)" else "  (OFF)").append('\n')
            append("Scans OK/attempted: ").append(BridgeCore.scansDone).append('/').append(BridgeCore.scanAttempts)
                .append("   failures: ").append(BridgeCore.scanFailures).append('\n')
            append("Snapshot: ").append(SnapshotTool.status).append('\n')
            BridgeCore.memoryProducer?.let { mp ->
                append("Memory bridge: ").append(if (mp.running) "running" else "stopped")
                    .append("  reads ").append(mp.readsOk).append('/').append(mp.readsOk + mp.readsFailed).append('\n')
                // Name the profile the producer actually holds, not the selected
                // game: those two silently diverged, and the status line said
                // "unbound" for both so there was nothing to notice.
                append("Memory profile: ").append(BridgeCore.memoryGameKey ?: "—")
                    .append(if (BridgeCore.memoryGameKey != BridgeCore.gameKey) "  ⚠ STALE — game is ${BridgeCore.gameKey}" else "")
                    .append('\n')
                append("RetroArch running: ").append(GameDetect.describe()).append('\n')
                append("Memory state: ").append(mp.lastStateSummary).append('\n')
                append("Memory error: ").append(mp.lastError).append('\n')
            }
            append("Last failure: ").append(BridgeCore.lastFailure).append('\n')
            append("Last read: ").append(BridgeCore.lastOcrSummary).append('\n')
            append("Raw text seen:\n")
            if (BridgeCore.rawLines.isEmpty()) append("  (nothing yet)")
            else BridgeCore.rawLines.forEach { append("  | ").append(it.take(48)).append('\n') }
            append('\n')
            append("Last emit: ").append(BridgeCore.lastEmit)
        }
    }

    private fun prefs() = getSharedPreferences("nuzbridge", MODE_PRIVATE)
}
