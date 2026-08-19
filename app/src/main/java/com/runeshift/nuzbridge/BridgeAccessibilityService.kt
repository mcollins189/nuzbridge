package com.runeshift.nuzbridge

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

/**
 * The capture loop. Exists solely for the takeScreenshot() capability —
 * accessibility events are ignored. Every SCAN_INTERVAL_MS (while scanning is
 * enabled from the app UI): screenshot the chosen display → ML Kit Latin OCR →
 * OcrParser → BridgeCore.emit (which broadcasts to the tracker over ws).
 */
class BridgeAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var parser: OcrParser? = null
    private var parserGame: String? = null
    private var busy = false

    private val tick = object : Runnable {
        override fun run() {
            val memoryFresh = System.currentTimeMillis() - BridgeCore.lastMemoryPollAt < 5000
            if (BridgeCore.scanning && !busy && !memoryFresh) scanOnce()
            handler.postDelayed(this, BridgeCore.SCAN_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        BridgeCore.serviceConnected = true
        // Restore config from prefs HERE, not just in MainActivity — after a
        // background process restart the service comes back without the UI
        // ever opening, and it must resume with the user's last settings
        // (display, game, and whether scanning was on) instead of defaults.
        BridgeCore.appContext = applicationContext
        val prefs = getSharedPreferences("nuzbridge", MODE_PRIVATE)
        BridgeCore.displayId = prefs.getInt("displayId", BridgeCore.displayId)
        BridgeCore.scanning = prefs.getBoolean("scanning", BridgeCore.scanning)
        // A tracker-pushed profile wins over the bundled assets; fall back to
        // the asset for the saved game key when none has ever been pushed.
        if (!BridgeCore.loadRemoteProfile(this)) {
            val game = prefs.getString("game", BridgeCore.gameKey) ?: BridgeCore.gameKey
            if (BridgeCore.profile == null || BridgeCore.profile?.key != game) {
                runCatching { BridgeCore.loadProfile(this, game) }
            }
        }
        BridgeCore.ensureServer()
        // Memory bridge resumes across service restarts too.
        if (prefs.getBoolean("memory", false)) BridgeCore.startMemoryProducer(this)
        handler.post(tick)
        BridgeCore.notifyChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        BridgeCore.serviceConnected = false
        handler.removeCallbacks(tick)
        BridgeCore.stopServer()
        BridgeCore.notifyChanged()
    }

    private fun scanOnce() {
        val profile = BridgeCore.profile ?: return
        if (parser == null || parserGame != profile.key) {
            parser = OcrParser(profile)
            parserGame = profile.key
        }
        busy = true
        BridgeCore.scanAttempts++
        takeScreenshot(
            BridgeCore.displayId,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        result.hardwareBuffer.close()
                        if (bmp == null) { fail("bitmap copy failed"); return }
                        recognize(bmp)
                    } catch (e: Throwable) {
                        fail("capture: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
                    }
                }
                override fun onFailure(errorCode: Int) {
                    fail(screenshotErrorName(errorCode))
                }
            }
        )
    }

    private fun fail(reason: String) {
        BridgeCore.scanFailures++
        BridgeCore.lastFailure = reason
        busy = false
        BridgeCore.notifyChanged()
    }

    private fun screenshotErrorName(code: Int): String = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "screenshot: internal error"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "screenshot: no accessibility access"
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "screenshot: rate limited (harmless if occasional)"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "screenshot: INVALID DISPLAY — pick the other screen in the dropdown"
        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "screenshot: app blocks capture (secure window)"
        else -> "screenshot: error $code"
    }

    /**
     * OCR quality lever: DON'T feed ML Kit the raw full frame. The GBA pixel
     * font is a tiny sliver of a 1080×1240 screenshot (and ML Kit downscales
     * large inputs further), so glyphs reach the model minuscule and blocky.
     * Split into top/bottom halves and bilinear-upscale each 2× — glyphs
     * arrive twice as large with smoothed strokes, which is what the
     * recognizer is trained on. Line positions are mapped back to full-screen
     * space so the parser's geometry rules are unchanged.
     */
    private fun recognize(bmp: Bitmap) {
        val w = bmp.width; val h = bmp.height
        if (w < 16 || h < 16) { bmp.recycle(); busy = false; return }
        val topSrc = Bitmap.createBitmap(bmp, 0, 0, w, h / 2)
        val botSrc = Bitmap.createBitmap(bmp, 0, h / 2, w, h - h / 2)
        bmp.recycle()
        val top = Bitmap.createScaledBitmap(topSrc, w * 2, topSrc.height * 2, true)
        val bot = Bitmap.createScaledBitmap(botSrc, w * 2, botSrc.height * 2, true)
        if (top !== topSrc) topSrc.recycle()
        if (bot !== botSrc) botSrc.recycle()

        val all = ArrayList<OcrLine>()
        val fail = { e: Exception ->
            BridgeCore.scanFailures++
            BridgeCore.lastFailure = "ocr: ${e.message ?: e.javaClass.simpleName}"
        }
        recognizer.process(InputImage.fromBitmap(top, 0))
            .addOnSuccessListener { t ->
                val th = top.height.toFloat().coerceAtLeast(1f)
                for (b in t.textBlocks) for (l in b.lines)
                    all.add(OcrLine(l.text, ((l.boundingBox?.exactCenterY() ?: 0f) / th) * 0.5f))
            }
            .addOnFailureListener(fail)
            .addOnCompleteListener {
                top.recycle()
                recognizer.process(InputImage.fromBitmap(bot, 0))
                    .addOnSuccessListener { t ->
                        val bh = bot.height.toFloat().coerceAtLeast(1f)
                        for (b in t.textBlocks) for (l in b.lines)
                            all.add(OcrLine(l.text, 0.5f + ((l.boundingBox?.exactCenterY() ?: 0f) / bh) * 0.5f))
                    }
                    .addOnFailureListener(fail)
                    .addOnCompleteListener {
                        bot.recycle()
                        finishScan(all)
                    }
            }
    }

    private fun finishScan(lines: List<OcrLine>) {
        val p = parser
        if (p == null) { busy = false; return }
        val r = p.feed(lines)
        BridgeCore.scansDone++
        BridgeCore.lastOcrSummary = r.summary
        BridgeCore.rawLines = lines.take(10).map { "${"%.0f".format(it.y * 100)}% ${it.text}" }
        if (r.changed) BridgeCore.emit(r.route, r.encounter, r.allies, r.alliesRaw)
        else BridgeCore.notifyChanged()
        busy = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
