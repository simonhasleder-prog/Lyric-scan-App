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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private val prefs by lazy {
        getSharedPreferences("lyricscan_models", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialDecoder = prefs.getString("decoder_uri", null)
            ?.let { Uri.parse(it) }
            ?.takeIf { isUriReadable(it) }

        val initialEncoder = prefs.getString("encoder_uri", null)
            ?.let { Uri.parse(it) }
            ?.takeIf { isUriReadable(it) }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LyricScanScreen(
                        initialDecoder = initialDecoder,
                        initialEncoder = initialEncoder,
                        onSaveDecoder = { uri ->
                            persistUriPermission(uri)
                            prefs.edit().putString("decoder_uri", uri.toString()).apply()
                            Toast.makeText(this, "Decoder gespeichert", Toast.LENGTH_SHORT).show()
                        },
                        onSaveEncoder = { uri ->
                            persistUriPermission(uri)
                            prefs.edit().putString("encoder_uri", uri.toString()).apply()
                            Toast.makeText(this, "Encoder gespeichert", Toast.LENGTH_SHORT).show()
                        },
                        onPersistAudio = { uri ->
                            persistUriPermission(uri)
                        },
                        getFileName = { uri -> getFileName(uri) },
                        getFileSizeMb = { uri -> getFileSizeMb(uri) },
                        onCopyText = { text ->
                            if (text.isNotBlank()) {
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Lyrics", text))
                                Toast.makeText(this, "Kopiert", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onRunPipeline = { audioUri, decoderUri, encoderUri, onStatus, onResult ->
                            lifecycleScope.launch {
                                runWhisperPipeline(
                                    audioUri = audioUri,
                                    decoderUri = decoderUri,
                                    encoderUri = encoderUri,
                                    onStatus = onStatus,
                                    onResult = onResult
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun LyricScanScreen(
        initialDecoder: Uri?,
        initialEncoder: Uri?,
        onSaveDecoder: (Uri) -> Unit,
        onSaveEncoder: (Uri) -> Unit,
        onPersistAudio: (Uri) -> Unit,
        getFileName: (Uri) -> String,
        getFileSizeMb: (Uri) -> Long,
        onCopyText: (String) -> Unit,
        onRunPipeline: (
            audioUri: Uri,
            decoderUri: Uri,
            encoderUri: Uri,
            onStatus: (String) -> Unit,
            onResult: (String) -> Unit
        ) -> Unit
    ) {
        var audioUri by remember { mutableStateOf<Uri?>(null) }
        var decoderUri by remember { mutableStateOf(initialDecoder) }
        var encoderUri by remember { mutableStateOf(initialEncoder) }
        var liveStatus by remember { mutableStateOf("Status: Bereit") }
        var resultText by remember { mutableStateOf("") }
        var isRunning by remember { mutableStateOf(false) }

        val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                onPersistAudio(uri)
                audioUri = uri
            }
        }

        val decoderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                decoderUri = uri
                onSaveDecoder(uri)
            }
        }

        val encoderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                encoderUri = uri
                onSaveEncoder(uri)
            }
        }

        val audioSummary = audioUri?.let { "1. Audio: ${getFileName(it)}" } ?: "1. Audio: nicht gewählt"
        val decoderSummary = decoderUri?.let { "2. Decoder: ${getFileSizeMb(it)} MB (gespeichert)" }
            ?: "2. Decoder: nicht gewählt"
        val encoderSummary = encoderUri?.let { "3. Encoder: ${getFileSizeMb(it)} MB (gespeichert)" }
            ?: "3. Encoder: nicht gewählt"

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "LyricScan",
                style = MaterialTheme.typography.headlineMedium
            )

            Text(text = "$audioSummary\n$decoderSummary\n$encoderSummary")
            Text(text = liveStatus)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { audioPicker.launch(arrayOf("audio/*", "*/*")) },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Audio")
                }
                Button(
                    onClick = { decoderPicker.launch(arrayOf("*/*")) },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Decoder")
                }
                Button(
                    onClick = { encoderPicker.launch(arrayOf("*/*")) },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Encoder")
                }
            }

            Button(
                onClick = {
                    val a = audioUri
                    val d = decoderUri
                    val e = encoderUri
                    if (a == null || d == null || e == null) {
                        liveStatus = "Status: Bitte Audio, Decoder und Encoder wählen."
                        return@Button
                    }
                    isRunning = true
                    resultText = ""
                    onRunPipeline(
                        a,
                        d,
                        e,
                        { status -> liveStatus = status },
                        { res ->
                            resultText = res
                            isRunning = false
                        }
                    )
                },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isRunning) "Läuft..." else "Transkribieren")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onCopyText(resultText) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Kopieren")
                }
                OutlinedButton(
                    onClick = {
                        resultText = ""
                        liveStatus = "Status: Textfeld geleert."
                    },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Leeren")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(text = resultText)
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

    private fun isUriReadable(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
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

    private fun copyUriToCacheFile(uri: Uri, outFile: File): File {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Konnte Datei-Stream nicht öffnen.")
        val buffer = ByteArray(64 * 1024)
        input.use { inStream ->
            FileOutputStream(outFile).use { outStream ->
                while (true) {
                    val read = inStream.read(buffer)
                    if (read <= 0) break
                    outStream.write(buffer, 0, read)
                }
                outStream.flush()
            }
        }
        return outFile
    }

    private suspend fun runWhisperPipeline(
        audioUri: Uri,
        decoderUri: Uri,
        encoderUri: Uri,
        onStatus: (String) -> Unit,
        onResult: (String) -> Unit
    ) {
        withContext(Dispatchers.Main) {
            onStatus("Status: Lade Audio & berechne Spektrogramm...")
        }

        try {
            val outputText = withContext(Dispatchers.Default) {
                val pcm16k = decodeAudioToMono16k(audioUri)
                val targetSamples = 16000 * 30
                val fixedPcm = FloatArray(targetSamples)
                val copyLen = min(pcm16k.size, targetSamples)
                System.arraycopy(pcm16k, 0, fixedPcm, 0, copyLen)

                val melFeatures = computeLogMelSpectrogram(fixedPcm, 80, 3000)

                withContext(Dispatchers.Main) {
                    onStatus("Status: Kopiere & lade Encoder/Decoder...")
                }

                val env = OrtEnvironment.getEnvironment()
                val encoderFile = copyUriToCacheFile(encoderUri, File(cacheDir, "whisper_encoder.onnx"))
                val decoderFile = copyUriToCacheFile(decoderUri, File(cacheDir, "whisper_decoder.onnx"))

                val encoderSession = env.createSession(encoderFile.absolutePath)
                val decoderSession = env.createSession(decoderFile.absolutePath)

                val decoderInputNamesList = decoderSession.inputNames.toList()
                var firstBestId: Int? = null
                var matchedInputsCount = 0

                val inputShape = longArrayOf(1, 80, 3000)
                val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(melFeatures), inputShape)

                val encoderInputName = encoderSession.inputNames.first()
                val encoderOutputs = encoderSession.run(mapOf(encoderInputName to inputTensor))
                val hiddenStates = encoderOutputs[0].value

                withContext(Dispatchers.Main) {
                    onStatus("Status: Encoder OK – erzeuge Tokens...")
                }

                val encoderHiddenTensor = OnnxTensor.createTensor(env, hiddenStates)
                val tokens = mutableListOf(50258L, 50359L, 50363L)

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
                    if (step == 0) {
                        matchedInputsCount = decoderInputs.size
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
                    if (step == 0) {
                        firstBestId = bestId
                    }

                    tokenTensor.close()
                    decOut.close()

                    if (bestId.toLong() == 50257L) break
                    tokens.add(bestId.toLong())
                }

                inputTensor.close()
                encoderOutputs.close()
                encoderHiddenTensor.close()
                encoderSession.close()
                decoderSession.close()

                "Fertig (${tokens.size} Tokens).\n" +
                    "1. bestId: $firstBestId | gematchte Inputs: $matchedInputsCount/${decoderInputNamesList.size}\n" +
                    "Decoder-Inputs: ${decoderInputNamesList.joinToString(", ")}\n" +
                    "Token-IDs: ${tokens.drop(3).joinToString(" ")}"
            }

            withContext(Dispatchers.Main) {
                onStatus("Status: Fertig!")
                onResult(outputText)
            }
        } catch (t: Throwable) {
            withContext(Dispatchers.Main) {
                onStatus("Status: Fehler aufgetreten.")
                onResult("Fehler: ${t.javaClass.simpleName}: ${t.message}")
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
                val shortBuf = outBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
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
