package com.lyricscan.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioFormat
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LyricScanScreen()
                }
            }
        }
    }
}

private fun getDisplayName(context: Context, uri: Uri): String {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(nameIndex)
        }
    }
    return name ?: (uri.lastPathSegment ?: "Audiodatei")
}

private suspend fun decodeAudioTo16kMonoFloat(
    context: Context,
    uri: Uri
): FloatArray = withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
        ?: throw IllegalStateException("Audiodatei konnte nicht geöffnet werden.")

    afd.use { descriptor ->
        extractor.setDataSource(
            descriptor.fileDescriptor,
            descriptor.startOffset,
            descriptor.length
        )

        var audioTrackIndex = -1
        var targetFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                targetFormat = format
                break
            }
        }

        if (audioTrackIndex < 0 || targetFormat == null) {
            throw IllegalStateException("Keine Audiospur in der Datei gefunden.")
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = targetFormat.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalStateException("Unbekanntes Audioformat.")

        val codec = MediaCodec.createDecoderByType(mime)
        val samples = ArrayList<Float>(16000 * 30)

        try {
            codec.configure(targetFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    presentationTimeUs,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val pcmBytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(pcmBytes)

                            val shortBuffer = ByteBuffer.wrap(pcmBytes)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                            val channels = targetFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                .coerceAtLeast(1)

                            while (shortBuffer.hasRemaining()) {
                                var sum = 0f
                                for (ch in 0 until channels) {
                                    if (shortBuffer.hasRemaining()) {
                                        sum += shortBuffer.get() / 32768.0f
                                    }
                                }
                                samples.add((sum / channels).coerceIn(-1.0f, 1.0f))
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        val encoding = if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        if (encoding != AudioFormat.ENCODING_PCM_16BIT) {
                            throw IllegalStateException("PCM-Format nicht unterstützt: $encoding")
                        }
                    }
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
            extractor.release()
        }

        val sourceRate = if (targetFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            targetFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            16000
        }

        if (samples.isEmpty()) {
            throw IllegalStateException("Aus der Audiodatei konnten keine Samples gelesen werden.")
        }

        val rawFloats = samples.toFloatArray()
        if (sourceRate == 16000) {
            return@withContext rawFloats
        }

        val targetLength = ((rawFloats.size.toLong() * 16000L) / sourceRate.toLong())
            .toInt()
            .coerceAtLeast(1)
        val resampled = FloatArray(targetLength)
        val ratio = rawFloats.size.toDouble() / targetLength.toDouble()
        for (i in 0 until targetLength) {
            val srcIndex = (i * ratio).toInt().coerceIn(0, rawFloats.lastIndex)
            resampled[i] = rawFloats[srcIndex]
        }
        resampled
    }
}

private suspend fun runOfflineWhisperSmallSafely(
    context: Context,
    audioUri: Uri?,
    modelFileName: String = "whisper-small.onnx",
    onStatusUpdate: (String) -> Unit,
    onResultText: (String) -> Unit
) {
    if (audioUri == null) {
        onStatusUpdate("Bitte zuerst eine Audiodatei auswählen.")
        return
    }

    try {
        onStatusUpdate("Dekodiere Audio für Offline-Whisper (small)...")
        val audioSamples16k = decodeAudioTo16kMonoFloat(context, audioUri)

        val result = withContext(Dispatchers.IO) {
            val modelFile = File(context.filesDir, modelFileName)
            val assetExists = try {
                context.assets.list("")?.contains(modelFileName) == true
            } catch (_: Exception) {
                false
            }

            if (!modelFile.exists() && !assetExists) {
                throw IllegalStateException(
                    "Modell '$modelFileName' noch nicht gefunden (${audioSamples16k.size} Samples bereit)."
                )
            }

            "Audio dekodiert (${audioSamples16k.size} Samples @ 16 kHz). Modell '$modelFileName' gefunden."
        }

        onResultText(result)
        onStatusUpdate("Offline-Whisper-Test abgeschlossen.")
    } catch (e: Throwable) {
        val message = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
        onStatusUpdate("Hinweis: $message")
    }
}

@Composable
private fun LyricScanScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("Keine Datei ausgewählt") }
    var statusText by remember { mutableStateOf("Bereit") }
    var lyricsText by remember { mutableStateOf("") }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            selectedFileName = getDisplayName(context, uri)
            statusText = "Ausgewählt: $selectedFileName"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "LyricScan",
            style = MaterialTheme.typography.headlineMedium
        )

        Button(
            onClick = { audioPicker.launch("audio/*") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Audiodatei wählen")
        }

        Text(
            text = selectedFileName,
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = {
                scope.launch {
                    runOfflineWhisperSmallSafely(
                        context = context,
                        audioUri = selectedUri,
                        onStatusUpdate = { statusText = it },
                        onResultText = { lyricsText = it }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Offline transkribieren (Whisper small)")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Lyrics", lyricsText))
                    Toast.makeText(context, "Kopiert", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Kopieren")
            }

            OutlinedButton(
                onClick = {
                    lyricsText = ""
                    statusText = "Text geleert"
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Leeren")
            }
        }

        Text(
            text = "Status: $statusText",
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedTextField(
            value = lyricsText,
            onValueChange = { lyricsText = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp),
            label = { Text("Lyrics / Ergebnis") }
        )
    }
}
