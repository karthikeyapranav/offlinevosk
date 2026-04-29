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
    private val CHANNELS = AudioFormat.CHANNEL_IN_MONO
    private val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    override fun getName() = "Vosk"

    @ReactMethod
    fun loadModel(promise: Promise) {
        executor.execute {
            try {
                if (model == null) model = Model(copyModelFromAssets())
                promise.resolve("Model Loaded")
            } catch (e: Exception) { promise.reject("MODEL_ERROR", e.message) }
        }
    }

    private fun copyModelFromAssets(): String {
        val dir = File(reactContext.filesDir, "model")
        if (!dir.exists() || dir.list()?.isEmpty() == true) {
            dir.mkdirs(); copyAssetFolder("model", dir)
        }
        return dir.absolutePath
    }

    private fun copyAssetFolder(path: String, dest: File) {
        val assets = reactContext.assets
        assets.list(path)?.forEach { f ->
            val full = "$path/$f"; val out = File(dest, f)
            if (assets.list(full)?.isNotEmpty() == true) {
                out.mkdirs(); copyAssetFolder(full, out)
            } else {
                assets.open(full).use { i -> FileOutputStream(out).use { o -> i.copyTo(o) } }
            }
        }
    }

    @ReactMethod
    fun startListening(promise: Promise) {
        if (isRecording) { promise.resolve("Already recording"); return }
        try {
            val bufSize = maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING), 4096)
            recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNELS, ENCODING, bufSize)
            recorder?.startRecording()
            isRecording = true
            
            executor.execute {
                val buf = ByteArray(4096)
                FileOutputStream(rawPcmFile()).use { fos ->
                    while (isRecording) {
                        val n = recorder?.read(buf, 0, buf.size) ?: break
                        if (n > 0) fos.write(buf, 0, n)
                    }
                }
            }
            promise.resolve("Started")
        } catch (e: Exception) { promise.reject("ERR", e.message) }
    }

    @ReactMethod
    fun stopListening(promise: Promise) {
        isRecording = false
        recorder?.stop(); recorder?.release(); recorder = null

        executor.execute {
            try {
                val raw = rawPcmFile()
                if (!raw.exists()) { promise.resolve("No audio recorded"); return@execute }

                val samples = readPcmFile(raw)
                val boosted = applyGain(samples, 15000f)

                sendEvent("onStatus", "Running Deep Analysis...")

                // We run TWO loops with different start offsets to catch missed words
                val pass1Words = runOffsetLoop(boosted, 0)
                val pass2Words = runOffsetLoop(boosted, SAMPLE_RATE * 1) // Offset by 1 second

                // Merge the two lists to find missing words
                val finalText = mergePasses(pass1Words, pass2Words)

                sendEvent("onFinalResult", finalText)
                raw.delete()
                promise.resolve("Done")
            } catch (e: Exception) { promise.reject("ERR", e.message) }
        }
    }

    private fun runOffsetLoop(samples: ShortArray, startOffset: Int): List<String> {
        val wordList = mutableListOf<String>()
        val rec = Recognizer(model, SAMPLE_RATE.toFloat())
        
        val WINDOW_SIZE = SAMPLE_RATE * 4 // 4 second window
        val STEP_SIZE = SAMPLE_RATE * 2   // 2 second step
        
        var pos = startOffset
        while (pos < samples.size) {
            val end = minOf(pos + WINDOW_SIZE, samples.size)
            if (end - pos < SAMPLE_RATE) break // Ignore tiny chunks

            val chunk = samples.copyOfRange(pos, end)
            rec.acceptWaveForm(shortsToBytes(chunk), chunk.size * 2)
            
            val result = JSONObject(rec.result).optString("text", "")
            if (result.isNotEmpty()) {
                wordList.addAll(result.split(" "))
            }
            pos += STEP_SIZE
        }
        rec.close()
        return wordList
    }

    private fun mergePasses(list1: List<String>, list2: List<String>): String {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // Add words from pass 1
        for (word in list1) {
            if (word.isNotEmpty() && word != "ఒకటి" && !seen.contains(word)) {
                result.add(word)
                seen.add(word)
            }
        }

        // Add words from pass 2 ONLY if they were missed in pass 1
        for (word in list2) {
            if (word.isNotEmpty() && word != "ఒకటి" && !seen.contains(word)) {
                // To keep some order, we find where it should go (basic logic: append at end)
                result.add(word)
                seen.add(word)
            }
        }
        
        return result.joinToString(" ")
    }

    private fun applyGain(samples: ShortArray, targetRMS: Float): ShortArray {
        var sum = 0.0
        for (s in samples) sum += s.toDouble().pow(2)
        val currentRMS = sqrt(sum / samples.size).toFloat()
        val gain = if (currentRMS > 0) targetRMS / currentRMS else 1.0f
        return ShortArray(samples.size) { (samples[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun readPcmFile(file: File): ShortArray {
        val b = file.readBytes()
        return ShortArray(b.size / 2) { i ->
            ((b[i*2+1].toInt() shl 8) or (b[i*2].toInt() and 0xFF)).toShort()
        }
    }

    private fun shortsToBytes(s: ShortArray): ByteArray {
        val b = ByteArray(s.size * 2)
        for (i in s.indices) {
            b[i*2] = (s[i].toInt() and 0xFF).toByte()
            b[i*2+1] = (s[i].toInt() shr 8 and 0xFF).toByte()
        }
        return b
    }

    private fun rawPcmFile() = File(reactContext.cacheDir, "temp.pcm")
    
    private fun sendEvent(name: String, text: String) {
        val map = Arguments.createMap().apply { putString("text", text) }
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(name, map)
    }
}