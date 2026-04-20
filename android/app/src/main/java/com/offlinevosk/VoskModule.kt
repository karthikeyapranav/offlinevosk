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

class VoskModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private var model: Model? = null
    private var recorder: AudioRecord? = null
    @Volatile private var isRecording = false
    private val executor = Executors.newSingleThreadExecutor()

    private val SAMPLE_RATE = 16000
    private val CHANNELS    = AudioFormat.CHANNEL_IN_MONO
    private val ENCODING    = AudioFormat.ENCODING_PCM_16BIT

    // Config from JS
    private var cfgGlobalGain       = 1.5f
    private var cfgNumPasses        = 3

    override fun getName() = "Vosk"
    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}

    @ReactMethod
    fun setConfig(cfg: ReadableMap, promise: Promise) {
        if (cfg.hasKey("globalGain"))   cfgGlobalGain = cfg.getDouble("globalGain").toFloat()
        if (cfg.hasKey("numPasses"))    cfgNumPasses  = cfg.getInt("numPasses")
        promise.resolve("Config updated")
    }

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
            else assets.open(full).use { inp -> FileOutputStream(out).use { o -> inp.copyTo(o) } }
        }
    }

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
                sendEvent("onStatus", "Processing $cfgNumPasses passes...")

                var bestTranscript = ""
                var bestLength = 0

                for (pass in 1..cfgNumPasses) {
                    // Vary gain from 0.7x to 1.3x base gain
                    val gainFactor = 0.7f + (pass - 1) * 0.3f
                    val passGain = cfgGlobalGain * gainFactor
                    val transcript = processPcmFile(raw, passGain)
                    sendEvent("onStatus", "Pass $pass (gain ${passGain}x): ${if (transcript.isEmpty()) "[no speech]" else transcript.take(60)}")

                    if (transcript.length > bestLength) {
                        bestLength = transcript.length
                        bestTranscript = transcript
                    }
                }

                if (bestTranscript.isNotEmpty()) {
                    sendEvent("onFinalResult", bestTranscript)
                } else {
                    sendEvent("onStatus", "No speech detected in any pass")
                }
                raw.delete()
                promise.resolve("Done")
            } catch (e: Exception) { promise.reject("PROCESS_ERROR", e.message) }
        }
    }

    private fun processPcmFile(file: File, gain: Float): String {
        var samples = readPcmFile(file)

        // Apply gain (simple multiplication, but prevent clipping)
        val gainLimited = gain.coerceIn(0.5f, 5f)  // max 5x to avoid distortion
        samples = applyGain(samples, gainLimited)

        // Run Vosk
        return runVosk(samples)
    }

    private fun applyGain(input: ShortArray, gain: Float): ShortArray {
        return ShortArray(input.size) { i ->
            val v = (input[i] * gain).toInt().coerceIn(-32768, 32767)
            v.toShort()
        }
    }

    private fun runVosk(samples: ShortArray): String {
        val rec = Recognizer(model, SAMPLE_RATE.toFloat())
        val bytes = shortsToBytes(samples)
        val chunkSize = 4096
        var pos = 0
        val transcript = StringBuilder()

        while (pos + chunkSize <= bytes.size) {
            val chunk = bytes.copyOfRange(pos, pos + chunkSize)
            if (rec.acceptWaveForm(chunk, chunk.size)) {
                val text = extractField(rec.result, "text")
                if (text.isNotEmpty()) {
                    if (transcript.isNotEmpty()) transcript.append(" ")
                    transcript.append(text)
                }
            }
            pos += chunkSize
        }
        if (pos < bytes.size) {
            rec.acceptWaveForm(bytes.copyOfRange(pos, bytes.size), bytes.size - pos)
        }
        val finalText = extractField(rec.finalResult, "text")
        if (finalText.isNotEmpty()) {
            if (transcript.isNotEmpty()) transcript.append(" ")
            transcript.append(finalText)
        }
        rec.close()
        return transcript.toString()
    }

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
                val all = ShortArray(totalSamples)
                var totalRead = 0
                val buf = ByteArray(bufSize)

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

                val FRAME_SAMPLES = 320
                val rmsList = mutableListOf<Float>()
                var i = 0
                while (i + FRAME_SAMPLES <= totalRead) {
                    var sum = 0.0
                    for (j in i until i + FRAME_SAMPLES) sum += all[j].toDouble().pow(2)
                    rmsList.add(sqrt(sum / FRAME_SAMPLES).toFloat())
                    i += FRAME_SAMPLES
                }
                if (rmsList.isEmpty()) { promise.reject("DIAG_ERROR", "No audio"); return@execute }

                val sorted = rmsList.sorted()
                val p10 = sorted[(sorted.size * 0.10).toInt()]
                val p50 = sorted[(sorted.size * 0.50).toInt()]
                val p90 = sorted[(sorted.size * 0.90).toInt()]
                val avg = sorted.average().toFloat()

                val report = buildString {
                    appendLine("═══ DIAGNOSTIC REPORT ═══")
                    appendLine("RAW audio RMS:")
                    appendLine("  P10 : ${p10.toInt()}")
                    appendLine("  P50 : ${p50.toInt()}")
                    appendLine("  P90 : ${p90.toInt()}")
                    appendLine("  Avg : ${avg.toInt()}")
                    appendLine()
                    appendLine("Suggested gain: ${(8000f / p90).coerceIn(1f, 10f)}")
                }
                sendEvent("onDiagnostic", report)
                promise.resolve(report)
            } catch (e: Exception) { promise.reject("DIAG_ERROR", e.message) }
        }
    }

    @ReactMethod fun clearText(promise: Promise) { promise.resolve("Cleared") }

    private fun readPcmFile(file: File): ShortArray {
        val bytes = file.readBytes()
        return ShortArray(bytes.size / 2) { i ->
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() and 0xFF
            ((hi shl 8) or lo).toShort()
        }
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    private fun extractField(json: String, field: String): String {
        return try {
            JSONObject(json).optString(field, "").trim()
        } catch (_: Exception) { "" }
    }

    private fun rawPcmFile() = File(reactContext.cacheDir, "vosk_raw.pcm")

    private fun sendEvent(eventName: String, text: String) {
        val map = Arguments.createMap()
        map.putString("text", text)
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, map)
    }
}