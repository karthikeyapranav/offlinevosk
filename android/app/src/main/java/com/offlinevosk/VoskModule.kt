package com.offlinevosk

import android.media.*
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class VoskModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private var model: Model? = null
    private var recorder: AudioRecord? = null
    @Volatile private var isRecording = false

    private val recordExecutor     = Executors.newSingleThreadExecutor()
    private val transcribeExecutor = Executors.newSingleThreadExecutor()

    private val TARGET_RATE = 16000

    override fun getName(): String = "Vosk"
    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}

    // ── Model ──────────────────────────────────────────────────────────────

    @ReactMethod
    fun loadModel(promise: Promise) {
        transcribeExecutor.execute {
            try {
                if (model != null) { promise.resolve("Model already loaded"); return@execute }
                val modelPath = copyModelFromAssets()
                overwriteModelConf(modelPath)
                model = Model(modelPath)
                promise.resolve("Model Loaded")
            } catch (e: Exception) { promise.reject("MODEL_ERROR", e.message) }
        }
    }

    /**
     * Always write our desired model.conf regardless of what was copied from assets.
     * Chain TDNN needs:
     *   beam=16, max-active=7000, lattice-beam=8, acoustic-scale=1.0
     */
    private fun overwriteModelConf(modelPath: String) {
        val confDir  = File(modelPath, "conf")
        confDir.mkdirs()
        val confFile = File(confDir, "model.conf")
        confFile.writeText("""
            --min-active=200
            --max-active=7000
            --beam=16.0
            --lattice-beam=8.0
            --acoustic-scale=1.0
            --frame-subsampling-factor=3
            --endpoint.silence-phones=1:2:3:4:5:6:7:8:9:10
            --endpoint.rule2.min-trailing-silence=0.5
            --endpoint.rule3.min-trailing-silence=1.0
            --endpoint.rule4.min-trailing-silence=2.0
        """.trimIndent())
        Log.d("Vosk", "model.conf written to ${confFile.absolutePath}")
        Log.d("Vosk", confFile.readText())
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
            else assets.open(full).use { i -> FileOutputStream(out).use { i.copyTo(it) } }
        }
    }

    // ── Microphone ─────────────────────────────────────────────────────────

    @ReactMethod
    fun startListening(promise: Promise) {
        if (model == null) { promise.reject("MODEL_NOT_LOADED", "Load model first"); return }
        if (isRecording)   { promise.resolve("Already listening"); return }
        try {
            val bufSize = maxOf(
                AudioRecord.getMinBufferSize(TARGET_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                4096
            )
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, TARGET_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            recorder!!.startRecording()
            isRecording = true
            recordExecutor.execute {
                val buf = ByteArray(bufSize)
                FileOutputStream(rawPcmFile()).use { fos ->
                    while (isRecording) {
                        val n = recorder?.read(buf, 0, buf.size) ?: break
                        if (n > 0) fos.write(buf, 0, n)
                    }
                }
            }
            promise.resolve("Started")
        } catch (e: Exception) { promise.reject("LISTEN_ERROR", e.message) }
    }

    @ReactMethod
    fun stopListening(promise: Promise) {
        if (!isRecording) { promise.resolve("Not recording"); return }
        isRecording = false
        recorder?.stop(); recorder?.release(); recorder = null
        transcribeExecutor.execute {
            try {
                val file = rawPcmFile()
                if (!file.exists() || file.length() == 0L) {
                    sendText("onFinalResult", ""); promise.resolve("No audio"); return@execute
                }
                sendText("onStatus", "Transcribing…")
                val text = transcribePcm(file.readBytes())
                file.delete()
                sendText("onFinalResult", text)
                promise.resolve("Done")
            } catch (e: Exception) { promise.reject("STOP_ERROR", e.message) }
        }
    }

    // ── transcribeUrl ──────────────────────────────────────────────────────

    @ReactMethod
    fun transcribeUrl(audioUrl: String, promise: Promise) {
        if (model == null) { promise.reject("MODEL_NOT_LOADED", "Load model first"); return }
        transcribeExecutor.execute {
            try {
                sendText("onStatus", "Downloading…")
                val tmp  = File(reactContext.cacheDir, "vosk_dl.wav")
                val conn = URL(audioUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true; conn.connect()
                if (conn.responseCode !in 200..299) {
                    promise.reject("DOWNLOAD_ERR", "HTTP ${conn.responseCode}"); return@execute
                }
                conn.inputStream.use { inp -> FileOutputStream(tmp).use { inp.copyTo(it) } }
                conn.disconnect()
                Log.d("Vosk", "Downloaded ${tmp.length()} bytes")

                val raw = tmp.readBytes()
                tmp.delete()

                val info = parseWavHeader(raw)
                Log.d("Vosk", "WAV: ${info.sampleRate}Hz ${info.channels}ch ${info.bitsPerSample}bit dataOffset=${info.dataOffset}")
                sendText("onStatus", "WAV ${info.sampleRate}Hz ${info.channels}ch → converting…")

                val pcm = convertToMono16kHz16bit(raw, info)
                Log.d("Vosk", "PCM: ${pcm.size} bytes = ${pcm.size / 2 / TARGET_RATE}s")
                sendText("onStatus", "Transcribing…")

                val text = transcribePcm(pcm)
                Log.d("Vosk", "Result: '$text'")
                sendText("onFinalResult", text)
                promise.resolve("Done")
            } catch (e: Exception) {
                Log.e("Vosk", "transcribeUrl error", e)
                promise.reject("TRANSCRIBE_ERR", e.message)
            }
        }
    }

    // ── WAV parsing ────────────────────────────────────────────────────────

    data class WavInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int,
                       val dataOffset: Int, val dataSize: Int)

    private fun parseWavHeader(raw: ByteArray): WavInfo {
        if (raw.size < 44 || String(raw, 0, 4) != "RIFF")
            return WavInfo(TARGET_RATE, 1, 16, 0, raw.size)

        val buf           = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val channels      = buf.getShort(22).toInt() and 0xFFFF
        val sampleRate    = buf.getInt(24)
        val bitsPerSample = buf.getShort(34).toInt() and 0xFFFF

        // Walk chunks to find "data"
        var offset = 12
        while (offset + 8 <= raw.size) {
            val id        = String(raw, offset, 4)
            val chunkSize = ByteBuffer.wrap(raw, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") return WavInfo(sampleRate, channels, bitsPerSample, offset + 8, chunkSize)
            offset += 8 + chunkSize
        }
        return WavInfo(sampleRate, channels, bitsPerSample, 44, raw.size - 44)
    }

    // ── Audio conversion ───────────────────────────────────────────────────

    private fun convertToMono16kHz16bit(raw: ByteArray, info: WavInfo): ByteArray {
        val dataEnd = minOf(info.dataOffset + info.dataSize, raw.size)
        val data    = raw.copyOfRange(info.dataOffset, dataEnd)

        // → 16-bit samples
        val samples16: ShortArray = when (info.bitsPerSample) {
            8  -> ShortArray(data.size) { i ->
                    ((data[i].toInt() and 0xFF) - 128).shl(8).toShort() }
            16 -> ShortArray(data.size / 2) { i ->
                    val lo = data[i * 2].toInt() and 0xFF
                    val hi = data[i * 2 + 1].toInt() and 0xFF
                    ((hi shl 8) or lo).toShort() }
            24 -> ShortArray(data.size / 3) { i ->
                    val b1 = data[i * 3 + 1].toInt() and 0xFF
                    val b2 = data[i * 3 + 2].toInt()
                    ((b2 shl 8) or b1).toShort() }
            32 -> ShortArray(data.size / 4) { i ->
                    (ByteBuffer.wrap(data, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int shr 16).toShort() }
            else -> throw IllegalArgumentException("Unsupported bits: ${info.bitsPerSample}")
        }

        // → mono
        val mono: ShortArray = if (info.channels == 1) samples16 else
            ShortArray(samples16.size / info.channels) { i ->
                var s = 0L
                for (c in 0 until info.channels) s += samples16[i * info.channels + c]
                (s / info.channels).toShort() }

        // → 16 kHz (linear interpolation)
        val resampled: ShortArray = if (info.sampleRate == TARGET_RATE) mono else {
            val ratio  = info.sampleRate.toDouble() / TARGET_RATE
            val outLen = (mono.size / ratio).toInt()
            ShortArray(outLen) { outIdx ->
                val src  = outIdx * ratio
                val idx  = src.toInt()
                val frac = src - idx
                val s0   = mono.getOrElse(idx)     { 0 }.toDouble()
                val s1   = mono.getOrElse(idx + 1) { 0 }.toDouble()
                (s0 + frac * (s1 - s0)).toInt().coerceIn(-32768, 32767).toShort() }
        }

        // → bytes (little-endian PCM)
        return ByteArray(resampled.size * 2) { i ->
            if (i % 2 == 0) (resampled[i / 2].toInt() and 0xFF).toByte()
            else            (resampled[i / 2].toInt() shr 8 and 0xFF).toByte()
        }
    }

    // ── Core transcription ─────────────────────────────────────────────────
    // Matches Python logic exactly:
    //   - One Recognizer per audio file (no mid-audio restarts that break word boundaries)
    //   - 8000-byte chunks = 4000 frames × 2 bytes, matching Python's readframes(4000)
    //   - Always flush with finalResult at the end

    private fun transcribePcm(pcm: ByteArray): String {
        val all       = mutableListOf<String>()
        val chunkSize = 8000  // 4000 frames × 2 bytes — matches Python readframes(4000)

        val rec = Recognizer(model, TARGET_RATE.toFloat())
        var pos = 0

        while (pos < pcm.size) {
            val end   = minOf(pos + chunkSize, pcm.size)
            val chunk = pcm.copyOfRange(pos, end)
            pos = end

            try {
                if (rec.acceptWaveForm(chunk, chunk.size)) {
                    val seg = extractText(rec.result)
                    if (seg.isNotEmpty()) {
                        all.add(seg)
                        Log.d("Vosk", "seg: $seg")
                    }
                }
            } catch (e: Exception) {
                Log.w("Vosk", "chunk err: ${e.message}")
                break
            }
        }

        // Always flush remaining audio — matches Python's FinalResult()
        try {
            val last = extractText(rec.finalResult)
            if (last.isNotEmpty()) {
                all.add(last)
                Log.d("Vosk", "flush: $last")
            }
        } catch (e: Exception) {
            Log.w("Vosk", "flush err: ${e.message}")
        }

        rec.close()
        return all.joinToString(" ").trim()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun extractText(json: String): String =
        try { JSONObject(json).optString("text", "").trim() } catch (_: Exception) { "" }

    private fun rawPcmFile() = File(reactContext.cacheDir, "vosk_mic.pcm")

    private fun sendText(event: String, text: String) {
        val map = Arguments.createMap().apply { putString("text", text) }
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(event, map)
    }
}