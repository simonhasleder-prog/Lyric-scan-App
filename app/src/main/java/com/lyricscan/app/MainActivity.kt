package com.lyricscan.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatusSummary: TextView
    private lateinit var tvLiveStatus: TextView
    private lateinit var tvResult: TextView

    private var audioUri: Uri? = null
    private var decoderUri: Uri? = null
    private var encoderUri: Uri? = null

    private val prefs by lazy {
        getSharedPreferences("lyricscan_models", Context.MODE_PRIVATE)
    }

    private val pickAudioLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                persistUriPermission(uri)
                audioUri = uri
                updateSummary()
            }
        }

    private val pickDecoderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                persistUriPermission(uri)
                decoderUri = uri
                prefs.edit().putString("decoder_uri", uri.toString()).apply()
                updateSummary()
                Toast.makeText(this, "Decoder dauerhaft gespeichert", Toast.LENGTH_SHORT).show()
            }
        }

    private val pickEncoderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                persistUriPermission(uri)
                encoderUri = uri
                prefs.edit().putString("encoder_uri", uri.toString()).apply()
                updateSummary()
                Toast.makeText(this, "Encoder dauerhaft gespeichert", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatusSummary = findViewById(R.id.tvStatusSummary)
        tvLiveStatus = findViewById(R.id.tvLiveStatus)
        tvResult = findViewById(R.id.tvResult)

        val btnPickAudio = findViewById<Button>(R.id.btnPickAudio)
        val btnPickDecoder = findViewById<Button>(R.id.btnPickDecoder)
        val btnPickEncoder = findViewById<Button>(R.id.btnPickEncoder)
        val btnRun = findViewById<Button>(R.id.btnRun)
        val btnCopy = findViewById<Button>(R.id.btnCopy)
        val btnClear = findViewById<Button>(R.id.btnClear)

        restoreSavedModels()
        updateSummary()

        btnPickAudio.setOnClickListener {
            pickAudioLauncher.launch(arrayOf("audio/*", "*/*"))
        }

        btnPickDecoder.setOnClickListener {
            pickDecoderLauncher.launch(arrayOf("*/*"))
        }

        btnPickEncoder.setOnClickListener {
            pickEncoderLauncher.launch(arrayOf("*/*"))
        }

        btnRun.setOnClickListener {
            val aUri = audioUri
            val dUri = decoderUri
            val eUri = encoderUri

            if (aUri == null || dUri == null || eUri == null) {
                tvLiveStatus.text = "Status: Bitte zuerst Audio, Decoder und Encoder wählen."
                return@setOnClickListener
            }

            lifecycleScope.launch {
                runWhisperPipeline(aUri, dUri, eUri)
            }
        }

        btnCopy.setOnClickListener {
            val text = tvResult.text.toString()
            if (text.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Lyrics", text))
                Toast.makeText(this, "Kopiert", Toast.LENGTH_SHORT).show()
            }
        }

        btnClear.setOnClickListener {
            tvResult.text = ""
            tvLiveStatus.text = "Status: Textfeld geleert."
        }
    }

    private fun persistUriPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    private fun restoreSavedModels() {
        prefs.getString("decoder_uri", null)?.let { saved ->
            val uri = Uri.parse(saved)
            if (isUriReadable(uri)) decoderUri = uri
        }
        prefs.getString("encoder_uri", null)?.let { saved ->
            val uri = Uri.parse(saved)
            if (isUriReadable(uri)) encoderUri = uri
        }
    }

    private fun isUriReadable(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun updateSummary() {
        val audioLine = audioUri?.let { "1. Audio: ${getFileName(it)}" } ?: "1. Audio: keine"
        val decoderLine = decoderUri?.let { "2. Decoder: ${getFileSizeMb(it)} MB (gespeichert & bereit)" }
            ?: "2. Decoder: nicht gewählt"
        val encoderLine = encoderUri?.let { "3. Encoder: ${getFileSizeMb(it)} MB (gespeichert & bereit)" }
            ?: "3. Encoder: nicht gewählt"

        tvStatusSummary.text = "$audioLine\n$decoderLine\n$encoderLine"
    }

    private fun getFileName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "Datei"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun getFileSizeMb(uri: Uri): Long {
        var bytes = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst()) {
                bytes = cursor.getLong(idx)
            }
        }
        return bytes / (1024 * 1024)
    }

    private suspend fun runWhisperPipeline(audioUri: Uri, decoderUri: Uri, encoderUri: Uri) {
        withContext(Dispatchers.Main) {
            tvLiveStatus.text = "Status: Lade Audio & berechne Spektrogramm..."
            tvResult.text = ""
        }

        try {
            val resultText = withContext(Dispatchers.Default) {
                val pcm16k = decodeAudioToMono16k(audioUri)
                // Exakt 30 Sekunden = 480.000 Samples -> ergibt exakt 3000 Frames für Whisper
                val targetSamples = 16000 * 30
                val fixedPcm = FloatArray(targetSamples)
                val copyLen = min(pcm16k.size, targetSamples)
                System.arraycopy(pcm16k, 0, fixedPcm, 0, copyLen)

                val melFeatures = computeLogMelSpectrogram(fixedPcm, 80, 3000)

                withContext(Dispatchers.Main) {
                    tvLiveStatus.text = "Status: Lade Encoder & Decoder..."
                }

                val env = OrtEnvironment.getEnvironment()
                val encoderBytes = contentResolver.openInputStream(encoderUri)!!.use { it.readBytes() }
                val decoderBytes = contentResolver.openInputStream(decoderUri)!!.use { it.readBytes() }

                val encoderSession = env.createSession(encoderBytes)
                val decoderSession = env.createSession(decoderBytes)

                val inputShape = longArrayOf(1, 80, 3000)
                val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(melFeatures), inputShape)

                val encoderInputName = encoderSession.inputNames.first()
                val encoderOutputs = encoderSession.run(mapOf(encoderInputName to inputTensor))
                val hiddenStates = encoderOutputs[0].value

                withContext(Dispatchers.Main) {
                    tvLiveStatus.text = "Status: Encoder OK – erzeuge Text..."
                }

                val encoderHiddenTensor = OnnxTensor.createTensor(env, hiddenStates)
                val tokens = mutableListOf(50258L, 50359L, 50363L) // <|startoftranscript|><|transcribe|><|notimestamps|>

                for (step in 0 until 120) {
                    val tokenArray = tokens.toLongArray()
                    val tokenTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(tokenArray),
                        longArrayOf(1, tokenArray.size.toLong())
                    )

                    val decoderInputs = mutableMapOf<String, OnnxTensor>()
                    for (name in decoderSession.inputNames) {
                        if (name.contains("input_ids", ignoreCase = true)) {
                            decoderInputs[name] = tokenTensor
                        } else if (name.contains("encoder", ignoreCase = true)) {
                            decoderInputs[name] = encoderHiddenTensor
                        }
                    }

                    val decOut = decoderSession.run(decoderInputs)
                    val logits = decOut[0].value as Array<Array<FloatArray>>
                    val lastLogits = logits[0][tokenArray.size - 1]

                    var bestId = 0
                    var bestScore = Float.NEGATIVE_INFINITY
                    for (i in lastLogits.indices) {
                        if (lastLogits[i] > bestScore) {
                            bestScore = lastLogits[i]
                            bestId = i
                        }
                    }

                    tokenTensor.close()
                    decOut.close()

                    if (bestId.toLong() == 50257L) break // <|endoftext|>
                    tokens.add(bestId.toLong())
                }

                inputTensor.close()
                encoderOutputs.close()
                encoderHiddenTensor.close()
                encoderSession.close()
                decoderSession.close()

                "Transkription abgeschlossen (${tokens.size} Tokens erkannt).\n" +
                    "Hinweis: Für vollständigen Klartext ohne Token-IDs wird noch die tokenizer.json im Projekt eingebunden.\n" +
                    "Token-IDs: ${tokens.drop(3).joinToString(" ")}"
            }

            withContext(Dispatchers.Main) {
                tvLiveStatus.text = "Status: Fertig!"
                tvResult.text = resultText
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                tvLiveStatus.text = "Status: Fehler aufgetreten."
                tvResult.text = "Fehler: ${e.message}"
            }
        }
    }

    private fun decodeAudioToMono16k(uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(this, uri, null)

        var audioTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                format = f
                break
            }
        }
        if (audioTrackIndex < 0 || format == null) {
            extractor.release()
            return FloatArray(0)
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val rawSamples = ArrayList<Float>()
        val bufferInfo = MediaCodec.BufferInfo()
        var isEOS = false
        val maxInputSamples = sampleRate * 35

        while (!isEOS && rawSamples.size < maxInputSamples) {
            val inIdx = codec.dequeueInputBuffer(10000)
            if (inIdx >= 0) {
                val inBuf = codec.getInputBuffer(inIdx)!!
                val sampleSize = extractor.readSampleData(inBuf, 0)
                if (sampleSize < 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    isEOS = true
                } else {
                    codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outIdx >= 0) {
                val outBuf = codec.getOutputBuffer(outIdx)!!
                outBuf.position(bufferInfo.offset)
                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (shortBuf.remaining() >= channels) {
                    var sum = 0f
                    for (c in 0 until channels) {
                        sum += shortBuf.get() / 32768.0f
                    }
                    rawSamples.add(sum / channels)
                }
                codec.releaseOutputBuffer(outIdx, false)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        if (sampleRate == 16000) return rawSamples.toFloatArray()

        val ratio = sampleRate.toDouble() / 16000.0
        val outLen = (rawSamples.size / ratio).toInt()
        return FloatArray(outLen) { i ->
            val srcIdx = min((i * ratio).toInt(), rawSamples.size - 1)
            rawSamples[srcIdx]
        }
    }

    private fun computeLogMelSpectrogram(pcm: FloatArray, nMels: Int, nFrames: Int): FloatArray {
        val nFft = 400
        val hopLength = 160
        val output = FloatArray(nMels * nFrames)
        val window = FloatArray(nFft) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / nFft))).toFloat()
        }

        for (frame in 0 until nFrames) {
            val start = frame * hopLength
            val mag = FloatArray(nFft / 2 + 1)
            for (k in mag.indices) {
                var re = 0.0
                var im = 0.0
                val angleStep = -2.0 * PI * k / nFft
                for (n in 0 until nFft) {
                    val sample = if (start + n < pcm.size) pcm[start + n] * window[n] else 0f
                    val angle = angleStep * n
                    re += sample * cos(angle)
                    im += sample * sin(angle)
                }
                mag[k] = (re * re + im * im).toFloat()
            }

            for (m in 0 until nMels) {
                val bin = min(((m.toDouble() / nMels) * (mag.size - 1)).toInt(), mag.size - 1)
                val logVal = ln(max(mag[bin], 1e-10f))
                output[m * nFrames + frame] = (logVal + 4.0f) / 4.0f
            }
        }
        return output
    }
}
