package com.offlinevosk

import android.media.*
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream 
import java.util.concurrent.Executors

class VoskModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var recorder: AudioRecord? = null
    private var isRecording = false
    private val executor = Executors.newSingleThreadExecutor()

    override fun getName(): String = "Vosk"

    // REQUIRED for NativeEventEmitter (IMPORTANT)
    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Int) {}

    // ================= MODEL =================

    @ReactMethod
    fun loadModel(promise: Promise) {
        executor.execute {
            try {
                if (model != null) {
                    promise.resolve("Model already loaded")
                    return@execute
                }

                val modelPath = copyModelFromAssets()
                model = Model(modelPath)

                promise.resolve("Model Loaded")
            } catch (e: Exception) {
                promise.reject("MODEL_ERROR", e.message)
            }
        }
    }

    private fun copyModelFromAssets(): String {
        val modelDir = File(reactContext.filesDir, "model")

        if (modelDir.exists() && modelDir.list()?.isNotEmpty() == true) {
            return modelDir.absolutePath
        }

        modelDir.mkdirs()
        copyAssetFolder("model", modelDir)
        return modelDir.absolutePath
    }

    private fun copyAssetFolder(assetPath: String, destDir: File) {
        val assets = reactContext.assets
        val files = assets.list(assetPath) ?: return

        for (file in files) {
            val fullPath = "$assetPath/$file"
            val destFile = File(destDir, file)
            val subFiles = assets.list(fullPath)

            if (subFiles != null && subFiles.isNotEmpty()) {
                destFile.mkdirs()
                copyAssetFolder(fullPath, destFile)
            } else {
                assets.open(fullPath).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    // ================= LIVE MIC =================

    @ReactMethod
    fun startListening(promise: Promise) {
        if (model == null) {
            promise.reject("MODEL_NOT_LOADED", "Load model first")
            return
        }

        if (isRecording) {
            promise.resolve("Already listening")
            return
        }

        try {
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            recognizer = Recognizer(model, sampleRate.toFloat())

            recorder?.startRecording()
            isRecording = true

            executor.execute {
                val buffer = ByteArray(bufferSize)

                while (isRecording) {
                    val read = recorder?.read(buffer, 0, buffer.size) ?: 0

                    if (read > 0) {
                        if (recognizer!!.acceptWaveForm(buffer, read)) {
                            sendEvent("onFinalResult", recognizer!!.result)
                        } else {
                            sendEvent("onPartialResult", recognizer!!.partialResult)
                        }
                    }
                }
            }

            promise.resolve("Listening Started")

        } catch (e: Exception) {
            promise.reject("LISTEN_ERROR", e.message)
        }
    }

    @ReactMethod
    fun stopListening(promise: Promise) {
        try {
            isRecording = false

            recorder?.stop()
            recorder?.release()
            recorder = null

            recognizer?.let {
                sendEvent("onFinalResult", it.finalResult)
                it.close()
            }

            recognizer = null

            promise.resolve("Stopped")

        } catch (e: Exception) {
            promise.reject("STOP_ERROR", e.message)
        }
    }

    private fun sendEvent(eventName: String, json: String) {
        val map = Arguments.createMap()

        try {
            val obj = JSONObject(json)
            val text = obj.optString("text", "")
            val partial = obj.optString("partial", "")

            if (text.isNotEmpty()) {
                map.putString("text", text)
            }

            if (partial.isNotEmpty()) {
                map.putString("text", partial)
            }

        } catch (e: Exception) {
            map.putString("text", "")
        }

        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, map)
    }
}