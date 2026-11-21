package com.lyricscan.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private var ortEnv: OrtEnvironment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ortEnv = OrtEnvironment.getEnvironment()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LyricScanScreen(
                        context = this,
                        env = ortEnv!!,
                        computeSpectrogram = { samples -> computeLogMelSpectrogram(samples) }
                    )
                }
            }
        }
    }

    private fun computeLogMelSpectrogram(
        samples: FloatArray,
        sampleRate: Int = 16000,
        nFft: Int = 400,
        hopLength: Int = 160,
        nMels: Int = 80
    ): Array<FloatArray> {
        val targetLength = sampleRate * 30
        val padded = FloatArray(targetLength)
        val copyCount = minOf(samples.size, targetLength)
        System.arraycopy(samples, 0, padded, 0, copyCount)

        val numFrames = 1 + (targetLength - nFft) / hopLength
        val hann = FloatArray(nFft) { i ->
            (0.5 * (1.0 - cos(2.0 * Math.PI * i / nFft))).toFloat()
        }

        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val fftBins = nFft / 2 + 1
        val melMin = hzToMel(0.0)
        val melMax = hzToMel(sampleRate / 2.0)
        val melPoints = DoubleArray(nMels + 2) { idx ->
            melToHz(melMin + (melMax - melMin) * idx / (nMels + 1))
        }
        val binFreqs = DoubleArray(fftBins) { k -> k.toDouble() * sampleRate / nFft }
        val filterbank = Array(nMels) { m ->
            FloatArray(fftBins) { k ->
                val left = melPoints[m]
                val center = melPoints[m + 1]
                val right = melPoints[m + 2]
                val freq = binFreqs[k]
                when {
                    freq > left && freq <= center -> ((freq - left) / (center - left)).toFloat()
                    freq > center && freq < right -> ((right - freq) / (right - center)).toFloat()
                    else -> 0f
                }
            }
        }

        val melSpec = Array(nMels) { FloatArray(numFrames) }
        val powerSpec = FloatArray(fftBins)

        for (frame in 0 until numFrames) {
            val start = frame * hopLength
            for (k in 0 until fftBins) {
                var real = 0.0
                var imag = 0.0
                val angleBase = -2.0 * Math.PI * k / nFft
                for (n in 0 until nFft) {
                    val windowed = padded[start + n] * hann[n]
                    val angle = angleBase * n
                    real += windowed * cos(angle)
                    imag += windowed * sin(angle)
                }
                powerSpec[k] = (real * real + imag * imag).toFloat()
            }

            for (m in 0 until nMels) {
                var energy = 0.0f
                val row = filterbank[m]
                for (k in 0 until fftBins) {
                    energy += row[k] * powerSpec[k]
                }
                melSpec[m][frame] = log10(maxOf(energy, 1e-10f).toDouble()).toFloat()
            }
        }

        var maxVal = Float.NEGATIVE_INFINITY
        for (m in 0 until nMels) {
            for (t in 0 until numFrames) {
                if (melSpec[m][t] > maxVal) maxVal = melSpec[m][t]
            }
        }
        val floorVal = maxVal - 8.0f
        for (m in 0 until nMels) {
            for (t in 0 until numFrames) {
                val clamped = maxOf(melSpec[m][t], floorVal)
                melSpec[m][t] = (clamped + 4.0f) / 4.0f
            }
        }

        return melSpec
    }
}

@Composable
private fun LyricScanScreen(
    context: Context,
    env: OrtEnvironment,
    computeSpectrogram: (FloatArray) -> Array<FloatArray>
) {
    val scope = rememberCoroutineScope()

    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var audioName by remember { mutableStateOf("keine") }
    var decoderUri by remember { mutableStateOf<Uri?>(null) }
    var decoderBytes by remember { mutableLongStateOf(0L) }
    var encoderUri by remember { mutableStateOf<Uri?>(null) }
    var encoderBytes by remember { mutableLongStateOf(0L) }
    var statusText by remember { mutableStateOf("Bereit für Encoder-Prüfung.") }
    var resultText by remember { mutableStateOf("") }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            audioUri = uri
            audioName = queryDisplayName(context, uri) ?: "Audio"
        }
    }

    val decoderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            decoderUri = uri
            decoderBytes = querySizeBytes(context, uri)
        }
    }

    val encoderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            encoderUri = uri
            encoderBytes = querySizeBytes(context, uri)
        }
    }

    val decoderMb = if (decoderBytes > 0L) decoderBytes / (1024L * 1024L) else 0L
    val encoderMb = if (encoderBytes > 0L) encoderBytes / (1024L * 1024L) else 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "LyricScan", style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "1. Audio: $audioName")
                Text(
                    text = if (decoderBytes > 0L) {
                        "2. Decoder: $decoderMb MB (bereit)"
                    } else {
                        "2. Decoder: nicht gewählt"
                    }
                )
                Text(
                    text = if (encoderBytes > 0L) {
                        "3. Encoder: $encoderMb MB (bereit)"
                    } else {
                        "3. Encoder: nicht gewählt"
                    }
                )
            }
        }

        Button(
            onClick = { audioPicker.launch(arrayOf("audio/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("1. Audiodatei wählen")
        }

        Button(
            onClick = { decoderPicker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("2. Decoder-Datei wählen (.onnx)")
        }

        Button(
            onClick = { encoderPicker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("3. Encoder-Datei wählen (.onnx)")
        }

        Button(
            onClick = {
                val currentAudioUri = audioUri
                val currentDecoderUri = decoderUri
                if (currentAudioUri == null || currentDecoderUri == null) {
                    Toast.makeText(context, "Bitte zuerst Audio und Decoder wählen", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                statusText = "Prüfe Audio, Spektrogramm, Encoder und Decoder..."
                scope.launch {
                    try {
                        val summary = withContext(Dispatchers.IO) {
                            val samples = decodeToMono16kFloatArray(context, currentAudioUri)
                            val melSpec = computeSpectrogram(samples)
                            val melBands = melSpec.size
                            val melFrames = melSpec.firstOrNull()?.size ?: 0

                            val decoderFile = copyUriToCache(context, currentDecoderUri, "decoder_model.onnx")
                            val decoderSessionOptions = OrtSession.SessionOptions()
                            val decoderInfo = env.createSession(decoderFile.absolutePath, decoderSessionOptions).use { session ->
                                "Decoder-Inputs: ${session.inputNames.joinToString(", ")}\nDecoder-Outputs: ${session.outputNames.joinToString(", ")}"
                            }

                            val encoderSummary = if (encoderUri != null) {
                                val encoderFile = copyUriToCache(context, encoderUri!!, "encoder_model.onnx")
                                val encoderSessionOptions = OrtSession.SessionOptions()
                                env.createSession(encoderFile.absolutePath, encoderSessionOptions).use { encSession ->
                                    val encInputName = encSession.inputNames.firstOrNull()
                                        ?: error("Encoder hat keinen Input")
                                    val flatMel = FloatArray(melBands * melFrames)
                                    var pos = 0
                                    for (m in 0 until melBands) {
                                        for (t in 0 until melFrames) {
                                            flatMel[pos++] = melSpec[m][t]
                                        }
                                    }
                                    val shape = longArrayOf(1L, melBands.toLong(), melFrames.toLong())
                                    OnnxTensor.createTensor(env, FloatBuffer.wrap(flatMel), shape).use { inputTensor ->
                                        encSession.run(mapOf(encInputName to inputTensor)).use { outputs ->
                                            val firstOut = outputs.firstOrNull()
                                            val outInfo = firstOut?.value as? OnnxTensor
                                            val outShape = outInfo?.info?.shape?.joinToString("x") ?: "unbekannt"
                                            "Encoder OK! Output-Shape: $outShape"
                                        }
                                    }
                                }
                            } else {
                                "Encoder übersprungen (keine Encoder-Datei gewählt)"
                            }

                            buildString {
                                appendLine("ONNX-Sessions bereit!")
                                appendLine("Audio-Samples: ${samples.size} (@ 16 kHz)")
                                appendLine("Log-Mel-Spektrogramm: ${melBands}x${melFrames} (Mels x Frames)")
                                appendLine(encoderSummary)
                                appendLine(decoderInfo)
                            }
                        }
                        resultText = summary
                        statusText = "Encoder-Schritt abgeschlossen."
                    } catch (e: Exception) {
                        statusText = "Fehler bei Encoder/Session."
                        resultText = "Fehler: ${e.message ?: e::class.java.simpleName}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("4. Session, Encoder & Audio prüfen")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("LyricScan", resultText))
                    Toast.makeText(context, "Kopiert", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Kopieren")
            }
            OutlinedButton(
                onClick = {
                    resultText = ""
                    statusText = "Ausgabe geleert."
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Leeren")
            }
        }

        Text(text = "Status: $statusText", style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            value = resultText,
            onValueChange = { resultText = it },
            label = { Text("Lyrics / Ergebnis") },
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return cursor.getString(idx)
        }
    }
    return null
}

private fun querySizeBytes(context: Context, uri: Uri): Long {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
        }
    }
    return context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
}

private fun copyUriToCache(context: Context, uri: Uri, fileName: String): File {
    val outFile = File(context.cacheDir, fileName)
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(outFile).use { output ->
            input.copyTo(output)
        }
    } ?: error("Konnte Modell-Stream nicht öffnen")
    return outFile
}

private fun decodeToMono16kFloatArray(context: Context, uri: Uri, targetSampleRate: Int = 16000): FloatArray {
    val extractor = MediaExtractor()
    extractor.setDataSource(context, uri, null)

    var audioTrackIndex = -1
    var audioFormat: MediaFormat? = null
    for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        if (mime.startsWith("audio/")) {
            audioTrackIndex = i
            audioFormat = format
            break
        }
    }
    require(audioTrackIndex >= 0 && audioFormat != null) { "Keine Audiospur gefunden" }

    extractor.selectTrack(audioTrackIndex)
    val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: error("Kein MIME-Typ")
    val sourceSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

    val codec = MediaCodec.createDecoderByType(mime)
    codec.configure(audioFormat, null, null, 0)
    codec.start()

    val bufferInfo = MediaCodec.BufferInfo()
    val monoSamples = ArrayList<Float>()
    var inputDone = false
    var outputDone = false

    while (!outputDone) {
        if (!inputDone) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inIndex)
                if (inputBuffer != null) {
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val pts = extractor.sampleTime
                        codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                        extractor.advance()
                    }
                }
            }
        }

        val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
        if (outIndex >= 0) {
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                outputDone = true
            }
            val outBuffer = codec.getOutputBuffer(outIndex)
            if (outBuffer != null && bufferInfo.size > 0) {
                outBuffer.position(bufferInfo.offset)
                outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                val shortBuffer = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val frameCount = shortBuffer.remaining() / channelCount
                for (f in 0 until frameCount) {
                    var sum = 0f
                    for (c in 0 until channelCount) {
                        sum += shortBuffer.get() / 32768f
                    }
                    monoSamples.add(sum / channelCount)
                }
            }
            codec.releaseOutputBuffer(outIndex, false)
        }
    }

    codec.stop()
    codec.release()
    extractor.release()

    val rawMono = monoSamples.toFloatArray()
    if (sourceSampleRate == targetSampleRate || rawMono.isEmpty()) {
        return rawMono
    }

    val ratio = targetSampleRate.toDouble() / sourceSampleRate.toDouble()
    val outLength = (rawMono.size * ratio).toInt().coerceAtLeast(1)
    return FloatArray(outLength) { i ->
        val srcPos = i / ratio
        val idx0 = srcPos.toInt().coerceIn(0, rawMono.lastIndex)
        val idx1 = (idx0 + 1).coerceIn(0, rawMono.lastIndex)
        val frac = (srcPos - idx0).toFloat()
        rawMono[idx0] * (1f - frac) + rawMono[idx1] * frac
    }
}
