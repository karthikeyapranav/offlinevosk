package com.offlinevosk

import android.media.*
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.*
import java.util.concurrent.Executors
import kotlin.math.*
import kotlin.random.Random

class VoskModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private var model: Model? = null
    private var recorder: AudioRecord? = null
    private var isRecording = false
    private val executor = Executors.newSingleThreadExecutor()

    private val SAMPLE_RATE   = 16000
    private val CHANNELS      = AudioFormat.CHANNEL_IN_MONO
    private val ENCODING      = AudioFormat.ENCODING_PCM_16BIT
    private val FRAME_SAMPLES = 320  // 20ms at 16kHz

    // ── Calibrated for YOUR mic (from diagnostic report) ──────────
    //
    //  Your numbers:  P10=54  P50=574  P90=1450  Max=3541
    //  Vosk sweet spot: 6000–10000 RMS
    //
    //  CRITICAL ORDERING INSIGHT:
    //  All previous versions detected silence BEFORE amplifying.
    //  So a speech frame at RMS=300 was thrown away as "silence"
    //  before the gain of 4x ever ran. Fixed below: amplify FIRST.
    //
    //  New pipeline:
    //    raw PCM
    //      → 1. high-pass filter        (remove hum)
    //      → 2. hard amplify ×GAIN      (bring everything up)
    //      → 3. silence detection       (NOW on amplified signal)
    //      → 4. per-frame normalize     (equalize soft words)
    //      → 5. comfort noise injection (keep Vosk alive at pauses)
    //      → 6. Vosk
    // ──────────────────────────────────────────────────────────────

    // Step 2: Hard amplify — brings your P90 from 1450 → ~6000
    // Formula: TARGET_RMS / your_P90 = 6000 / 1450 ≈ 4.1
    // Set slightly higher (5.0) to cover soft speech below P90
    private val HARD_GAIN = 5.0f

    // Step 3: Silence threshold — evaluated AFTER hard amplify
    // After 5x gain: your P10(54)→270, P50(574)→2870, P90(1450)→7250
    // Set threshold between amplified noise floor and amplified speech
    // 270 (noise floor after gain) vs 2870 (median speech after gain)
    // Midpoint ≈ 1500 — captures even your soft speech
    private val SILENCE_RMS_AFTER_GAIN = 1200f

    // Step 4: Per-frame normalize target (after hard gain, speech is ~3000-7000)
    // Push everything to 7000 to stress every syllable equally
    private val FRAME_TARGET_RMS = 7000f
    private val FRAME_MAX_GAIN   = 6f    // Cap: don't let noise frames explode (they're ~270 after gain)

    // Step 5: Comfort noise — filled into silence so Vosk decoder never resets
    // Must be BELOW SILENCE_RMS_AFTER_GAIN (1200) so Vosk ignores it
    // But above ~100 so decoder sees continuous audio
    private val COMFORT_NOISE_RMS  = 300f
    // Only start injecting after 400ms of consecutive silence (20 frames × 20ms)
    private val SILENCE_GAP_FRAMES = 20

    // High-pass filter coefficient
    private val HP_ALPHA = 0.97f

    override fun getName(): String = "Vosk"
    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}

    // ══════════════════════════════════════════════════
    //  MODEL
    // ══════════════════════════════════════════════════

    @ReactMethod
    fun loadModel(promise: Promise) {
        executor.execute {
            try {
                if (model != null) { promise.resolve("Model already loaded"); return@execute }
                model = Model(copyModelFromAssets())
                promise.resolve("Model Loaded")
            } catch (e: Exception) { promise.reject("MODEL_ERROR", e.message) }
        }
    }

    private fun copyModelFromAssets(): String {
        val dir = File(reactContext.filesDir, "model")
        if (dir.exists() && dir.list()?.isNotEmpty() == true) return dir.absolutePath
        dir.mkdirs(); copyAssetFolder("model", dir); return dir.absolutePath
    }

    private fun copyAssetFolder(path: String, dest: File) {
        val assets = reactContext.assets
        for (f in assets.list(path) ?: return) {
            val full = "$path/$f"; val out = File(dest, f)
            if (assets.list(full)?.isNotEmpty() == true) { out.mkdirs(); copyAssetFolder(full, out) }
            else assets.open(full).use { i -> FileOutputStream(out).use { o -> i.copyTo(o) } }
        }
    }

    // ══════════════════════════════════════════════════
    //  RECORD → raw PCM file
    // ══════════════════════════════════════════════════

    @ReactMethod
    fun startListening(promise: Promise) {
        if (model == null) { promise.reject("MODEL_NOT_LOADED", "Load model first"); return }
        if (isRecording)   { promise.resolve("Already recording"); return }
        try {
            val minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
            val bufSize = maxOf(minBuf, 4096)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNELS, ENCODING, bufSize
            )
            recorder?.startRecording()
            isRecording = true
            executor.execute { writeRawPcm(bufSize) }
            promise.resolve("Recording started")
        } catch (e: Exception) { promise.reject("RECORD_ERROR", e.message) }
    }

    private fun writeRawPcm(bufSize: Int) {
        val buf = ByteArray(bufSize)
        FileOutputStream(rawPcmFile()).use { fos ->
            while (isRecording) {
                val n = recorder?.read(buf, 0, buf.size) ?: break
                if (n > 0) fos.write(buf, 0, n)
            }
        }
    }

    // ══════════════════════════════════════════════════
    //  STOP → process → Vosk
    // ══════════════════════════════════════════════════

    @ReactMethod
    fun stopListening(promise: Promise) {
        if (!isRecording) { promise.resolve("Not recording"); return }
        isRecording = false
        recorder?.stop(); recorder?.release(); recorder = null

        executor.execute {
            try {
                val raw = rawPcmFile()
                if (!raw.exists() || raw.length() == 0L) {
                    promise.resolve("No audio"); return@execute
                }
                sendEvent("onStatus", "Processing…")

                var s = readPcmFile(raw)

                // ── CORRECT ORDER ──────────────────────────────────
                // 1. High-pass filter (remove hum — operates on quiet signal,
                //    does not depend on amplitude so order doesn't matter here)
                s = highPassFilter(s)

                // 2. HARD AMPLIFY FIRST — bring quiet mic up to Vosk-friendly levels
                //    Everything else runs on the amplified signal
                s = hardAmplify(s, HARD_GAIN)

                // 3. Per-frame normalize — NOW on amplified signal
                //    Each 20ms speech frame boosted to FRAME_TARGET_RMS
                //    silence gate uses SILENCE_RMS_AFTER_GAIN (post-amplify values)
                s = perFrameNormalize(s)

                // 4. Bridge silences — inject comfort noise into pauses
                //    so Vosk decoder context never resets mid-recording
                s = bridgeSilences(s)

                // 5. Vosk recognition on clean, loud, continuous audio
                val transcript = runVosk(s)

                if (transcript.isNotEmpty()) sendEvent("onFinalResult", transcript.trim())
                raw.delete()
                promise.resolve("Done")
            } catch (e: Exception) { promise.reject("PROCESS_ERROR", e.message) }
        }
    }

    @ReactMethod fun clearText(promise: Promise) { promise.resolve("Cleared") }

    // ══════════════════════════════════════════════════
    //  DIAGNOSE — records 5s and prints RMS stats
    //  Run this whenever environment changes
    // ══════════════════════════════════════════════════

    @ReactMethod
    fun diagnose(promise: Promise) {
        executor.execute {
            try {
                val minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
                val bufSize = maxOf(minBuf, 4096)
                val dr = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, CHANNELS, ENCODING, bufSize
                )
                sendEvent("onStatus", "Diagnosing — speak normally for 5 seconds…")
                dr.startRecording()

                val totalSamples = SAMPLE_RATE * 5
                val all          = ShortArray(totalSamples)
                var totalRead    = 0
                val buf          = ByteArray(bufSize)

                while (totalRead < totalSamples) {
                    val n = dr.read(buf, 0, buf.size.coerceAtMost((totalSamples - totalRead) * 2))
                    if (n <= 0) break
                    for (i in 0 until n / 2) {
                        if (totalRead + i < totalSamples) {
                            val lo = buf[i * 2].toInt() and 0xFF
                            val hi = buf[i * 2 + 1].toInt() and 0xFF
                            all[totalRead + i] = ((hi shl 8) or lo).toShort()
                        }
                    }
                    totalRead += n / 2
                }
                dr.stop(); dr.release()

                val rmsList = mutableListOf<Float>()
                var i = 0
                while (i + FRAME_SAMPLES <= totalRead) {
                    rmsList.add(rms(all, i, FRAME_SAMPLES)); i += FRAME_SAMPLES
                }
                if (rmsList.isEmpty()) { promise.reject("DIAG_ERROR", "No audio"); return@execute }

                val sorted = rmsList.sorted()
                val p10    = sorted[(sorted.size * 0.10).toInt()]
                val p50    = sorted[(sorted.size * 0.50).toInt()]
                val p90    = sorted[(sorted.size * 0.90).toInt()]
                val avg    = sorted.average().toFloat()

                // Compute what the values will look like AFTER hard gain
                val gainedP10 = p10  * HARD_GAIN
                val gainedP50 = p50  * HARD_GAIN
                val gainedP90 = p90  * HARD_GAIN

                val report = buildString {
                    appendLine("RAW (before processing)")
                    appendLine("  Min  : ${sorted.first().toInt()}")
                    appendLine("  Max  : ${sorted.last().toInt()}")
                    appendLine("  Avg  : ${avg.toInt()}")
                    appendLine("  P10  : ${p10.toInt()}  ← noise floor")
                    appendLine("  P50  : ${p50.toInt()}  ← typical speech")
                    appendLine("  P90  : ${p90.toInt()}  ← loud speech")
                    appendLine()
                    appendLine("AFTER ×${HARD_GAIN} gain")
                    appendLine("  P10  : ${gainedP10.toInt()}")
                    appendLine("  P50  : ${gainedP50.toInt()}")
                    appendLine("  P90  : ${gainedP90.toInt()}")
                    appendLine()
                    appendLine("VOSK sweet spot: 6000–10000")
                    appendLine("Your P90 after gain: ${gainedP90.toInt()}")
                    val status = when {
                        gainedP90 < 3000  -> "⚠ Too quiet — increase HARD_GAIN"
                        gainedP90 < 6000  -> "⚠ Below sweet spot — increase HARD_GAIN slightly"
                        gainedP90 < 12000 -> "✓ Good range"
                        else              -> "⚠ May clip — reduce HARD_GAIN"
                    }
                    appendLine("Status: $status")
                    appendLine()
                    appendLine("Current SILENCE_RMS_AFTER_GAIN: ${SILENCE_RMS_AFTER_GAIN.toInt()}")
                    appendLine("Your speech median after gain : ${gainedP50.toInt()}")
                    val threshOk = SILENCE_RMS_AFTER_GAIN < gainedP50
                    appendLine("Threshold OK? ${if (threshOk) "✓ Yes" else "✗ NO — threshold above speech!"}")
                }

                sendEvent("onDiagnostic", report)
                promise.resolve(report)
            } catch (e: Exception) { promise.reject("DIAG_ERROR", e.message) }
        }
    }

    // ══════════════════════════════════════════════════
    //  STEP 1 — Load PCM
    // ══════════════════════════════════════════════════

    private fun readPcmFile(file: File): ShortArray {
        val bytes = file.readBytes()
        return ShortArray(bytes.size / 2) { i ->
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() and 0xFF
            ((hi shl 8) or lo).toShort()
        }
    }

    // ══════════════════════════════════════════════════
    //  STEP 2 — High-pass filter
    // ══════════════════════════════════════════════════

    private fun highPassFilter(input: ShortArray): ShortArray {
        val out = ShortArray(input.size)
        var pIn = 0f; var pOut = 0f
        for (i in input.indices) {
            val x = input[i].toFloat()
            val y = HP_ALPHA * (pOut + x - pIn)
            out[i] = y.coerceIn(-32768f, 32767f).toInt().toShort()
            pIn = x; pOut = y
        }
        return out
    }

    // ══════════════════════════════════════════════════
    //  STEP 3 — Hard amplify
    //  Uniform gain across entire recording.
    //  Brings quiet mic (P90=1450) into Vosk range (6000+)
    //  MUST run before silence detection.
    // ══════════════════════════════════════════════════

    private fun hardAmplify(input: ShortArray, gain: Float): ShortArray =
        ShortArray(input.size) { i ->
            (input[i] * gain).coerceIn(-32768f, 32767f).toInt().toShort()
        }

    // ══════════════════════════════════════════════════
    //  STEP 4 — Per-frame normalize
    //  After hard amplify, speech frames are 2000–7000.
    //  This pushes ALL speech frames to FRAME_TARGET_RMS
    //  so every syllable — stressed or not — is equally
    //  loud when Vosk sees it.
    //
    //  Silence gate is checked AFTER hard amplify:
    //  SILENCE_RMS_AFTER_GAIN = 1200
    //  → speech frames (2000+) get normalized
    //  → noise frames (270) are left untouched (too low for FRAME_MAX_GAIN to blow up)
    // ══════════════════════════════════════════════════

    private fun perFrameNormalize(input: ShortArray): ShortArray {
        val out = input.copyOf()
        var i   = 0
        while (i + FRAME_SAMPLES <= input.size) {
            val frameRms = rms(input, i, FRAME_SAMPLES)
            if (frameRms > SILENCE_RMS_AFTER_GAIN) {
                val gain = (FRAME_TARGET_RMS / frameRms).coerceIn(1f, FRAME_MAX_GAIN)
                for (k in i until i + FRAME_SAMPLES) {
                    out[k] = (input[k] * gain).coerceIn(-32768f, 32767f).toInt().toShort()
                }
            }
            i += FRAME_SAMPLES
        }
        return out
    }

    // ══════════════════════════════════════════════════
    //  STEP 5 — Bridge silences with comfort noise
    //
    //  When Vosk gets a long run of near-zero samples it
    //  internally flushes its decoder and resets context.
    //  Words spoken after a pause lose all context from
    //  before the pause → missed words.
    //
    //  Fix: replace silence with low-amplitude white noise.
    //  RMS=300 is below SILENCE_RMS_AFTER_GAIN=1200 so
    //  Vosk won't hallucinate words, but it's enough to
    //  keep the decoder running continuously.
    //
    //  Only injected after SILENCE_GAP_FRAMES (400ms) of
    //  consecutive silence — short natural gaps between
    //  syllables are left untouched.
    // ══════════════════════════════════════════════════

    private fun bridgeSilences(input: ShortArray): ShortArray {
        val out           = input.copyOf()
        var silenceFrames = 0
        var i             = 0
        while (i + FRAME_SAMPLES <= input.size) {
            val frameRms = rms(input, i, FRAME_SAMPLES)
            if (frameRms < SILENCE_RMS_AFTER_GAIN) {
                silenceFrames++
                if (silenceFrames > SILENCE_GAP_FRAMES) {
                    for (k in i until i + FRAME_SAMPLES) {
                        out[k] = ((Random.nextFloat() * 2f - 1f) * COMFORT_NOISE_RMS)
                            .coerceIn(-32768f, 32767f).toInt().toShort()
                    }
                }
            } else {
                silenceFrames = 0
            }
            i += FRAME_SAMPLES
        }
        return out
    }

    // ══════════════════════════════════════════════════
    //  STEP 6 — Vosk recognition
    //  Single Recognizer for the whole recording.
    //  Comfort noise ensures context is never lost.
    // ══════════════════════════════════════════════════

    private fun runVosk(samples: ShortArray): String {
        val rec        = Recognizer(model, SAMPLE_RATE.toFloat())
        val transcript = StringBuilder()
        val bytes      = shortsToBytes(samples)
        val chunkSize  = 4096
        var pos        = 0

        while (pos + chunkSize <= bytes.size) {
            val chunk = bytes.copyOfRange(pos, pos + chunkSize)
            if (rec.acceptWaveForm(chunk, chunk.size)) {
                val text = extractField(rec.result, "text")
                if (text.isNotEmpty()) {
                    if (transcript.isNotEmpty()) transcript.append(" ")
                    transcript.append(text)
                    sendEvent("onPartialResult", transcript.toString().trim())
                }
            } else {
                val partial = extractField(rec.partialResult, "partial")
                if (partial.isNotEmpty()) sendEvent("onPartialResult", partial)
            }
            pos += chunkSize
        }
        if (pos < bytes.size) {
            val chunk = bytes.copyOfRange(pos, bytes.size)
            rec.acceptWaveForm(chunk, chunk.size)
        }
        val finalText = extractField(rec.finalResult, "text")
        if (finalText.isNotEmpty()) {
            if (transcript.isNotEmpty()) transcript.append(" ")
            transcript.append(finalText)
        }
        rec.close()
        return transcript.toString()
    }

    // ══════════════════════════════════════════════════
    //  UTILITIES
    // ══════════════════════════════════════════════════

    private fun rms(s: ShortArray, offset: Int, length: Int): Float {
        var sum = 0.0
        val end = (offset + length).coerceAtMost(s.size)
        for (i in offset until end) sum += s[i].toDouble().pow(2)
        return sqrt(sum / (end - offset)).toFloat()
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2]     = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    private fun extractField(json: String, field: String): String =
        try { JSONObject(json).optString(field, "").trim() } catch (_: Exception) { "" }

    private fun rawPcmFile() = File(reactContext.cacheDir, "vosk_raw.pcm")

    private fun sendEvent(eventName: String, text: String) {
        val map = Arguments.createMap()
        map.putString("text", text)
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, map)
    }
}