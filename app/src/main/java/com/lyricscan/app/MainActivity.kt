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
import org.json.JSONObject
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

        val initialTokenizer = prefs.getString("tokenizer_uri", null)
            ?.let { Uri.parse(it) }
            ?.takeIf { isUriReadable(it) }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LyricScanScreen(
                        initialDecoder = initialDecoder,
                        initialEncoder = initialEncoder,
                        initialTokenizer = initialTokenizer,
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
                        onSaveTokenizer = { uri ->
                            persistUriPermission(uri)
                            prefs.edit().putString("tokenizer_uri", uri.toString()).apply()
                            Toast.makeText(this, "Tokenizer gespeichert", Toast.LENGTH_SHORT).show()
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
                        onRunPipeline = { audioUri, decoderUri, encoderUri, tokenizerUri, onStatus, onResult ->
                            lifecycleScope.launch {
                                runWhisperPipeline(
                                    audioUri = audioUri,
                                    decoderUri = decoderUri,
                                    encoderUri = encoderUri,
                                    tokenizerUri = tokenizerUri,
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
        initialTokenizer: Uri?,
        onSaveDecoder: (Uri) -> Unit,
        onSaveEncoder: (Uri) -> Unit,
        onSaveTokenizer: (Uri) -> Unit,
        onPersistAudio: (Uri) -> Unit,
        getFileName: (Uri) -> String,
        getFileSizeMb: (Uri) -> Long,
        onCopyText: (String) -> Unit,
        onRunPipeline: (
            audioUri: Uri,
            decoderUri: Uri,
            encoderUri: Uri,
            tokenizerUri: Uri?,
            onStatus: (String) -> Unit,
            onResult: (String) -> Unit
        ) -> Unit
    ) {
        var audioUri by remember { mutableStateOf<Uri?>(null) }
        var decoderUri by remember { mutableStateOf(initialDecoder) }
        var encoderUri by remember { mutableStateOf(initialEncoder) }
        var tokenizerUri by remember { mutableStateOf(initialTokenizer) }
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

        val tokenizerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                tokenizerUri = uri
                onSaveTokenizer(uri)
            }
        }

        val audioSummary = audioUri?.let { "1. Audio: ${getFileName(it)}" } ?: "1. Audio: nicht gewählt"
        val decoderSummary = decoderUri?.let { "2. Decoder: ${getFileSizeMb(it)} MB (gespeichert)" }
            ?: "2. Decoder: nicht gewählt"
        val encoderSummary = encoderUri?.let { "3. Encoder: ${getFileSizeMb(it)} MB (gespeichert)" }
            ?: "3. Encoder: nicht gewählt"
        val tokenizerSummary = tokenizerUri?.let { "4. Tokenizer: ${getFileName(it)} (gespeichert)" }
            ?: "4. Tokenizer: nicht gewählt"

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

            Text(text = "$audioSummary\n$decoderSummary\n$encoderSummary\n$tokenizerSummary")
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

            OutlinedButton(
                onClick = { tokenizerPicker.launch(arrayOf("application/json", "*/*")) },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Tokenizer laden")
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
                        tokenizerUri,
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

    private fun buildByteToUnicodeMap(): Map<Char, Int> {
        val bs = ArrayList<Int>()
        for (b in 33..126) bs.add(b)
        for (b in 161..172) bs.add(b)
        for (b in 174..255) bs.add(b)

        val cs = ArrayList<Int>(bs)
        var n = 0
        for (b in 0..255) {
            if (!bs.contains(b)) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }

        val map = HashMap<Char, Int>(bs.size)
        for (i in bs.indices) {
            map[cs[i].toChar()] = bs[i]
        }
        return map
    }

    private fun decodeByteLevelToken(rawToken: String, byteToUnicode: Map<Char, Int>): String {
        val bytes = ArrayList<Byte>(rawToken.length)
        for (ch in rawToken) {
            val mappedByte = byteToUnicode[ch]
            if (mappedByte != null) {
                bytes.add(mappedByte.toByte())
            } else {
                val utf8Bytes = ch.toString().toByteArray(Charsets.UTF_8)
                for (b in utf8Bytes) bytes.add(b)
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun loadVocabFromTokenizerJson(tokenizerFile: File): Map<Long, String> {
        val jsonText = tokenizerFile.readText(Charsets.UTF_8)
        val root = JSONObject(jsonText)
        val idToToken = HashMap<Long, String>()

        val modelObj = root.optJSONObject("model")
        val vocabObj = modelObj?.optJSONObject("vocab")
        if (vocabObj != null) {
            val keys = vocabObj.keys()
            while (keys.hasNext()) {
                val tokenStr = keys.next()
                val id = vocabObj.optLong(tokenStr, -1L)
                if (id >= 0L) {
                    idToToken[id] = tokenStr
                }
            }
        }

        val addedTokens = root.optJSONArray("added_tokens")
        if (addedTokens != null) {
            for (i in 0 until addedTokens.length()) {
                val item = addedTokens.optJSONObject(i) ?: continue
                val id = item.optLong("id", -1L)
                val content = item.optString("content", "")
                if (id >= 0L && content.isNotEmpty()) {
                    idToToken[id] = content
                }
            }
        }

        return idToToken
    }

    private fun buildWhisperPromptTokens(tokenizerFile: File?): List<Long> {
        val defaultTokens = listOf(50258L, 50359L, 50363L)
        if (tokenizerFile == null || !tokenizerFile.exists() || tokenizerFile.length() == 0L) {
            return defaultTokens
        }

        return try {
            val idToToken = loadVocabFromTokenizerJson(tokenizerFile)
            val tokenToId = HashMap<String, Long>(idToToken.size)
            for ((id, tok) in idToToken) {
                tokenToId[tok] = id
            }

            val sot = tokenToId["<|startoftranscript|>"] ?: 50258L
            val transcribe = tokenToId["<|transcribe|>"] ?: 50359L
            val noTimestamps = tokenToId["<|notimestamps|>"] ?: 50363L

            listOf(sot, transcribe, noTimestamps)
        } catch (_: Exception) {
            defaultTokens
        }
    }

    private fun decodeBasicTokens(tokenIds: List<Long>, tokenizerFile: File? = null): String {
        if (tokenizerFile != null && tokenizerFile.exists() && tokenizerFile.length() > 0L) {
            return try {
                val idToToken = loadVocabFromTokenizerJson(tokenizerFile)
                val byteToUnicode = buildByteToUnicodeMap()
                val sb = StringBuilder()

                for (id in tokenIds) {
                    if (id >= 50257L) continue
                    val rawToken = idToToken[id]
                    if (rawToken == null) {
                        sb.append("[ID:$id]")
                    } else if (!rawToken.startsWith("<|")) {
                        sb.append(decodeByteLevelToken(rawToken, byteToUnicode))
                    }
                }
                sb.toString().trim()
            } catch (e: Exception) {
                "Tokenizer-Lesefehler: ${e.message}"
            }
        }

        val tokenMap: Map<Long, String> = mapOf(
            50257L to " ",
            13L to ".",
            11L to ",",
            0L to "!",
            30L to "?",
            522L to "[ID:522] ",
            4870L to "[ID:4870] ",
            1318L to "[ID:1318] ",
            8L to "[ID:8] "
        )

        val sb = StringBuilder()
        for (id in tokenIds) {
            if (id >= 50364L) continue
            val mapped = tokenMap[id]
            if (mapped != null) {
                sb.append(mapped)
            } else {
                sb.append("[ID:$id] ")
            }
        }
        return sb.toString().trim()
    }

    private suspend fun runWhisperPipeline(
        audioUri: Uri,
        decoderUri: Uri,
        encoderUri: Uri,
        tokenizerUri: Uri?,
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
                val tokenizerFile = tokenizerUri?.let { uri ->
                    try {
                        copyUriToCacheFile(uri, File(cacheDir, "tokenizer.json"))
                    } catch (_: Exception) {
                        null
                    }
                }

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
                val promptTokens = buildWhisperPromptTokens(tokenizerFile)
                val promptLen = promptTokens.size
                val tokens = promptTokens.toMutableList()

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

                    if (step > 0 && bestId.toLong() == 50257L) break
                    tokens.add(bestId.toLong())

                    val recentTail = tokens.takeLast(5)
                    if (recentTail.size == 5 && recentTail.all { it == bestId.toLong() }) {
                        break
                    }
                }

                inputTensor.close()
                encoderOutputs.close()
                encoderHiddenTensor.close()
                encoderSession.close()
                decoderSession.close()

                val generatedIds = tokens.drop(promptLen)
                val decodedPreview = decodeBasicTokens(generatedIds, tokenizerFile)
                val tokStatus = if (tokenizerFile != null && tokenizerFile.exists()) {
                    "Cache OK (${tokenizerFile.length() / 1024} KB)"
                } else {
                    "nicht gewählt"
                }

                "Fertig (${tokens.size} Tokens) | Tokenizer: $tokStatus.\n" +
                    "Prompt: ${promptTokens.joinToString(" ")}\n" +
                    "1. bestId: $firstBestId | gematchte Inputs: $matchedInputsCount/${decoderInputNamesList.size}\n" +
                    "Token-IDs: ${generatedIds.joinToString(" ")}\n" +
                    "Vorschau: $decodedPreview"
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

    private fun hzToMelSlaney(hz: Double): Double {
        val fMin = 0.0
        val fSp = 200.0 / 3.0
        var mel = (hz - fMin) / fSp
        val minLogHz = 1000.0
        val minLogMel = (minLogHz - fMin) / fSp
        val logStep = ln(6.4) / 27.0
        if (hz >= minLogHz) {
            mel = minLogMel + ln(hz / minLogHz) / logStep
        }
        return mel
    }

    private fun melToHzSlaney(mel: Double): Double {
        val fMin = 0.0
        val fSp = 200.0 / 3.0
        var hz = fMin + fSp * mel
        val minLogHz = 1000.0
        val minLogMel = (minLogHz - fMin) / fSp
        val logStep = ln(6.4) / 27.0
        if (mel >= minLogMel) {
            hz = minLogHz * kotlin.math.exp(logStep * (mel - minLogMel))
        }
        return hz
    }

    private fun buildWhisperMelFilterbank(
        nMels: Int,
        nFft: Int,
        sampleRate: Double = 16000.0
    ): Array<FloatArray> {
        val nFreqs = nFft / 2 + 1
        val filters = Array(nMels) { FloatArray(nFreqs) }

        val melMin = hzToMelSlaney(0.0)
        val melMax = hzToMelSlaney(sampleRate / 2.0)

        val melPoints = DoubleArray(nMels + 2) { i ->
            melMin + (melMax - melMin) * i / (nMels + 1)
        }
        val hzPoints = DoubleArray(nMels + 2) { i -> melToHzSlaney(melPoints[i]) }
        val fftFreqs = DoubleArray(nFreqs) { k -> k * sampleRate / nFft }

        for (m in 0 until nMels) {
            val fLeft = hzPoints[m]
            val fCenter = hzPoints[m + 1]
            val fRight = hzPoints[m + 2]

            for (k in 0 until nFreqs) {
                val freq = fftFreqs[k]
                if (freq > fLeft && freq < fCenter && fCenter > fLeft) {
                    filters[m][k] = ((freq - fLeft) / (fCenter - fLeft)).toFloat()
                } else if (freq >= fCenter && freq < fRight && fRight > fCenter) {
                    filters[m][k] = ((fRight - freq) / (fRight - fCenter)).toFloat()
                }
            }

            // Slaney-Normierung wie in Whisper
            val enorm = 2.0 / (hzPoints[m + 2] - hzPoints[m])
            for (k in 0 until nFreqs) {
                filters[m][k] = (filters[m][k] * enorm).toFloat()
            }
        }
        return filters
    }

    private fun computeLogMelSpectrogram(pcm: FloatArray, nMels: Int, nFrames: Int): FloatArray {
        val nFft = 400
        val hopLength = 160
        val nFreqs = nFft / 2 + 1
        val output = FloatArray(nMels * nFrames)
        val window = FloatArray(nFft) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / nFft))).toFloat()
        }
        val filterbank = buildWhisperMelFilterbank(nMels, nFft, 16000.0)

        var maxLogVal = Float.NEGATIVE_INFINITY
        val tempLog = FloatArray(nMels * nFrames)

        for (frame in 0 until nFrames) {
            val start = frame * hopLength
            val powerSpec = FloatArray(nFreqs)
            for (k in 0 until nFreqs) {
                var re = 0.0
                var im = 0.0
                val angleStep = -2.0 * PI * k / nFft
                for (n in 0 until nFft) {
                    val sample = if (start + n < pcm.size) pcm[start + n] * window[n] else 0f
                    val angle = angleStep * n
                    re += sample * cos(angle)
                    im += sample * sin(angle)
                }
                powerSpec[k] = (re * re + im * im).toFloat()
            }

            for (m in 0 until nMels) {
                var melEnergy = 0f
                val row = filterbank[m]
                for (k in 0 until nFreqs) {
                    melEnergy += row[k] * powerSpec[k]
                }
                val log10Val = (ln(max(melEnergy, 1e-10f).toDouble()) / ln(10.0)).toFloat()
                val idx = m * nFrames + frame
                tempLog[idx] = log10Val
                if (log10Val > maxLogVal) {
                    maxLogVal = log10Val
                }
            }
        }

        val floorVal = maxLogVal - 8.0f
        for (i in tempLog.indices) {
            val clamped = max(tempLog[i], floorVal)
            output[i] = (clamped + 4.0f) / 4.0f
        }
        return output
    }
}
