package com.lyricscan.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

data class SongSuggestion(
    val artist: String,
    val title: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6366F1),
                    background = Color(0xFF070A12),
                    surface = Color(0xFF0D1220),
                    onPrimary = Color.White,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF070A12)
                ) {
                    LyricScanStartScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricScanStartScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("lyricscan_prefs", Context.MODE_PRIVATE) }

    var artist by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var lyrics by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Bereit. Wähle eine Audiodatei oder suche nach Interpret & Titel.") }
    var isLoading by remember { mutableStateOf(false) }

    var selectedAudioUri by remember { mutableStateOf<Uri?>(null) }

    val modelFile = remember { File(context.filesDir, "whisper_small_int8_cpu_ort_1.18.0.onnx") }
    var isModelReady by remember { mutableStateOf(modelFile.exists() && modelFile.length() > 50_000_000L) }

    var localHistory by remember { mutableStateOf(loadHistory(prefs)) }
    var remoteSuggestions by remember { mutableStateOf<List<SongSuggestion>>(emptyList()) }
    var showArtistSuggestions by remember { mutableStateOf(false) }
    var showTitleSuggestions by remember { mutableStateOf(false) }
    var suggestionJob by remember { mutableStateOf<Job?>(null) }

    fun recordPair(a: String, t: String) {
        val cleanA = a.trim()
        val cleanT = t.trim()
        if (cleanA.isNotEmpty() || cleanT.isNotEmpty()) {
            val updated = (listOf(SongSuggestion(cleanA, cleanT)) + localHistory)
                .distinctBy { "${it.artist.lowercase()}|${it.title.lowercase()}" }
                .take(300)
            localHistory = updated
            saveHistory(prefs, updated)
        }
    }

    fun triggerRemoteSuggestions(queryText: String) {
        suggestionJob?.cancel()
        val trimmed = queryText.trim()
        if (trimmed.length < 2) {
            remoteSuggestions = emptyList()
            return
        }
        suggestionJob = scope.launch {
            delay(300)
            val found = searchLrclibSuggestions(trimmed)
            if (found.isNotEmpty()) {
                remoteSuggestions = found
            }
        }
    }

    val allKnownPairs = remember(localHistory, remoteSuggestions) {
        (localHistory + remoteSuggestions)
            .distinctBy { "${it.artist.lowercase()}|${it.title.lowercase()}" }
    }

    val artistSuggestions = remember(artist, allKnownPairs) {
        val q = artist.trim().lowercase()
        allKnownPairs
            .map { it.artist }
            .filter { it.isNotBlank() && (q.isEmpty() || it.lowercase().contains(q)) }
            .distinctBy { it.lowercase() }
            .take(6)
    }

    val titleSuggestions = remember(artist, title, allKnownPairs) {
        val artistFilter = artist.trim().lowercase()
        val titleFilter = title.trim().lowercase()

        val filteredByArtist = if (artistFilter.isNotEmpty()) {
            allKnownPairs.filter { it.artist.lowercase().contains(artistFilter) }
        } else {
            allKnownPairs
        }

        filteredByArtist
            .map { it.title }
            .filter { it.isNotBlank() && (titleFilter.isEmpty() || it.lowercase().contains(titleFilter)) }
            .distinctBy { it.lowercase() }
            .take(6)
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedAudioUri = uri
            var fileName = "Song.mp3"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    fileName = cursor.getString(idx) ?: fileName
                }
            }
            val cleanBase = fileName.substringBeforeLast(".").replace("_", " ").trim()
            if (cleanBase.contains(" - ")) {
                val parts = cleanBase.split(" - ", limit = 2)
                artist = parts[0].trim()
                title = parts[1].trim()
            } else {
                title = cleanBase
            }
            recordPair(artist, title)
            status = "Datei geladen: $fileName"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LyricScan", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D1220),
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { audioPicker.launch(arrayOf("audio/*")) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Audiodatei auswählen")
            }

            OutlinedTextField(
                value = artist,
                onValueChange = {
                    artist = it
                    showArtistSuggestions = true
                    triggerRemoteSuggestions(it)
                },
                label = { Text("Interpret") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (showArtistSuggestions && artistSuggestions.isNotEmpty() && artist.isNotBlank()) {
                Surface(
                    color = Color(0xFF131B2E),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        artistSuggestions.forEach { suggestionArtist ->
                            Text(
                                text = suggestionArtist,
                                color = Color.White,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        artist = suggestionArtist
                                        showArtistSuggestions = false
                                        triggerRemoteSuggestions(suggestionArtist)
                                        showTitleSuggestions = true
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    showTitleSuggestions = true
                    val combinedQuery = listOf(artist.trim(), it.trim()).filter { s -> s.isNotEmpty() }.joinToString(" ")
                    triggerRemoteSuggestions(combinedQuery)
                },
                label = {
                    Text(
                        if (artist.isNotBlank()) "Songtitel (nur von $artist)"
                        else "Songtitel"
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (showTitleSuggestions && titleSuggestions.isNotEmpty() && (title.isNotBlank() || artist.isNotBlank())) {
                Surface(
                    color = Color(0xFF131B2E),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        titleSuggestions.forEach { suggestionTitle ->
                            Text(
                                text = suggestionTitle,
                                color = Color.White,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        title = suggestionTitle
                                        showTitleSuggestions = false
                                        recordPair(artist, title)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    showArtistSuggestions = false
                    showTitleSuggestions = false
                    recordPair(artist, title)
                    scope.launch {
                        isLoading = true
                        status = "Suche Songtext online..."
                        val result = fetchLrclibLyrics(artist, title)
                        if (result != null) {
                            lyrics = result
                            status = "Songtext gefunden."
                        } else {
                            status = "Kein Online-Songtext gefunden. Nutze Offline-Whisper für deine Datei."
                        }
                        isLoading = false
                    }
                },
                enabled = !isLoading && (artist.isNotBlank() || title.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isLoading) "Bitte warten..." else "Songtext suchen (Online)")
            }

            Button(
                onClick = {
                    val uri = selectedAudioUri
                    if (uri == null) {
                        status = "Bitte zuerst oben eine Audiodatei auswählen."
                        return@Button
                    }
                    scope.launch {
                        isLoading = true
                        if (!isModelReady) {
                            status = "Lade Whisper-Small Modell einmalig herunter..."
                            val dlResult = downloadWhisperSmallModel(modelFile) { mbLoaded ->
                                status = "Lade Whisper-Small Modell: $mbLoaded MB heruntergeladen..."
                            }
                            if (dlResult.isFailure) {
                                status = "Download-Fehler: ${dlResult.exceptionOrNull()?.message}"
                                isLoading = false
                                return@launch
                            }
                            isModelReady = true
                        }

                        status = "Wandle Audio um & transkribiere offline mit Whisper-Small..."
                        val result = runOfflineWhisperSmall(context, uri, modelFile)
                        result.onSuccess { text ->
                            lyrics = text
                            recordPair(artist, title)
                            status = "Offline-Transkription (Whisper-Small) fertig."
                        }.onFailure { err ->
                            status = "Whisper-Fehler: ${err.message ?: "Unbekannter Fehler"}"
                        }
                        isLoading = false
                    }
                },
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0F766E),
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (isModelReady) "Offline Whisper (Small) starten"
                    else "Whisper-Small laden & starten (Offline)"
                )
            }

            Text(text = status, fontSize = 13.sp, color = Color(0xFF94A3B8))

            OutlinedTextField(
                value = lyrics,
                onValueChange = { lyrics = it },
                label = { Text("Songtext") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (lyrics.isNotBlank()) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Songtext", lyrics))
                            Toast.makeText(context, "Kopiert", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Kopieren")
                }

                Button(
                    onClick = {
                        lyrics = ""
                        Toast.makeText(context, "Text gelöscht", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB91C1C),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Text löschen")
                }
            }
        }
    }
}

private suspend fun downloadWhisperSmallModel(
    targetFile: File,
    onProgressMb: (Long) -> Unit
): Result<Unit> = withContext(Dispatchers.IO) {
    val modelUrl = "https://huggingface.co/khmyznikov/whisper-int8-cpu-ort.onnx/resolve/main/whisper_small_int8_cpu_ort_1.18.0.onnx"
    val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
    var conn: HttpURLConnection? = null
    try {
        conn = (URL(modelUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 60000
            setRequestProperty("User-Agent", "LyricScan-Android/1.0")
        }
        if (conn.responseCode !in 200..299) {
            return@withContext Result.failure(Exception("HTTP ${conn.responseCode}"))
        }
        conn.inputStream.use { input ->
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                var totalBytes = 0L
                var lastReportedMb = -1L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    totalBytes += read
                    val mb = totalBytes / (1024 * 1024)
                    if (mb != lastReportedMb) {
                        lastReportedMb = mb
                        withContext(Dispatchers.Main) {
                            onProgressMb(mb)
                        }
                    }
                }
                output.flush()
            }
        }
        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            return@withContext Result.failure(Exception("Modelldatei konnte nicht gespeichert werden."))
        }
        Result.success(Unit)
    } catch (e: Exception) {
        if (tempFile.exists()) tempFile.delete()
        Result.failure(e)
    } finally {
        conn?.disconnect()
    }
}

private suspend fun runOfflineWhisperSmall(
    context: Context,
    uri: Uri,
    modelFile: File
): Result<String> = withContext(Dispatchers.IO) {
    try {
        val pcmSamples = decodeAudioTo16kMonoFloat(context, uri)
        if (pcmSamples.isEmpty()) {
            return@withContext Result.failure(Exception("Audiodatei enthält keine lesbaren Audiodaten."))
        }

        val env = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions()

        sessionOptions.use { opts ->
            try {
                opts.registerCustomOpsLibrary(OrtxPackage.getLibraryPath())
            } catch (_: Throwable) {
                try {
                    OrtxPackage.register(opts)
                } catch (e: Throwable) {
                    return@withContext Result.failure(
                        Exception("ONNX Extensions konnten nicht registriert werden: ${e.message}")
                    )
                }
            }

            env.createSession(modelFile.absolutePath, opts).use { session ->
                val chunkSamples = 16000 * 30
                val fullTranscript = StringBuilder()
                var offset = 0

                while (offset < pcmSamples.size) {
                    val end = minOf(offset + chunkSamples, pcmSamples.size)
                    val chunk = pcmSamples.copyOfRange(offset, end)

                    val audioTensor = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(chunk),
                        longArrayOf(1, chunk.size.toLong())
                    )
                    val maxLenTensor = OnnxTensor.createTensor(
                        env,
                        IntBuffer.wrap(intArrayOf(200)),
                        longArrayOf(1)
                    )
                    val minLenTensor = OnnxTensor.createTensor(
                        env,
                        IntBuffer.wrap(intArrayOf(0)),
                        longArrayOf(1)
                    )
                    val numBeamsTensor = OnnxTensor.createTensor(
                        env,
                        IntBuffer.wrap(intArrayOf(1)),
                        longArrayOf(1)
                    )
                    val numReturnTensor = OnnxTensor.createTensor(
                        env,
                        IntBuffer.wrap(intArrayOf(1)),
                        longArrayOf(1)
                    )
                    val lengthPenaltyTensor = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(floatArrayOf(1.0f)),
                        longArrayOf(1)
                    )
                    val repetitionPenaltyTensor = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(floatArrayOf(1.0f)),
                        longArrayOf(1)
                    )

                    val inputs = mutableMapOf<String, OnnxTensor>()
                    val inputNames = session.inputNames
                    if ("audio_pcm" in inputNames) inputs["audio_pcm"] = audioTensor
                    if ("max_length" in inputNames) inputs["max_length"] = maxLenTensor
                    if ("min_length" in inputNames) inputs["min_length"] = minLenTensor
                    if ("num_beams" in inputNames) inputs["num_beams"] = numBeamsTensor
                    if ("num_return_sequences" in inputNames) inputs["num_return_sequences"] = numReturnTensor
                    if ("length_penalty" in inputNames) inputs["length_penalty"] = lengthPenaltyTensor
                    if ("repetition_penalty" in inputNames) inputs["repetition_penalty"] = repetitionPenaltyTensor

                    try {
                        session.run(inputs).use { outputs ->
                            val firstOut = outputs[0].value
                            val segmentText = when (firstOut) {
                                is Array<*> -> firstOut.flattenStrings().joinToString(" ")
                                else -> firstOut?.toString() ?: ""
                            }.trim()
                            if (segmentText.isNotEmpty()) {
                                if (fullTranscript.isNotEmpty()) fullTranscript.append("\n")
                                fullTranscript.append(segmentText)
                            }
                        }
                    } finally {
                        audioTensor.close()
                        maxLenTensor.close()
                        minLenTensor.close()
                        numBeamsTensor.close()
                        numReturnTensor.close()
                        lengthPenaltyTensor.close()
                        repetitionPenaltyTensor.close()
                    }

                    offset += chunkSamples
                }

                val finalStr = fullTranscript.toString().trim()
                if (finalStr.isEmpty()) {
                    Result.failure(Exception("Kein Gesangstext erkannt."))
                } else {
                    Result.success(finalStr)
                }
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private fun Array<*>.flattenStrings(): List<String> {
    val list = mutableListOf<String>()
    for (item in this) {
        when (item) {
            is String -> list.add(item)
            is Array<*> -> list.addAll(item.flattenStrings())
        }
    }
    return list
}

private fun decodeAudioTo16kMonoFloat(context: Context, uri: Uri): FloatArray {
    val extractor = MediaExtractor()
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        extractor.setDataSource(pfd.fileDescriptor)
    } ?: throw Exception("Audiodatei konnte nicht geöffnet werden.")

    var trackIndex = -1
    var trackFormat: MediaFormat? = null
    for (i in 0 until extractor.trackCount) {
        val fmt = extractor.getTrackFormat(i)
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
        if (mime.startsWith("audio/")) {
            trackIndex = i
            trackFormat = fmt
            break
        }
    }
    if (trackIndex < 0 || trackFormat == null) {
        extractor.release()
        throw Exception("Keine Audiospur in der Datei gefunden.")
    }

    extractor.selectTrack(trackIndex)
    val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
    var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var channelCount = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

    val codec = MediaCodec.createDecoderByType(mime)
    codec.configure(trackFormat, null, null, 0)
    codec.start()

    val pcmBytes = ByteArrayOutputStream()
    val bufferInfo = MediaCodec.BufferInfo()
    var inputDone = false
    var outputDone = false

    try {
        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outIdx >= 0) {
                val outBuf = codec.getOutputBuffer(outIdx)!!
                if (bufferInfo.size > 0) {
                    val chunk = ByteArray(bufferInfo.size)
                    outBuf.position(bufferInfo.offset)
                    outBuf.get(chunk)
                    pcmBytes.write(chunk)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true
                }
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFmt = codec.outputFormat
                if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channelCount = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        }
    } finally {
        codec.stop()
        codec.release()
        extractor.release()
    }

    val raw = pcmBytes.toByteArray()
    val shortBuf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val totalShorts = shortBuf.remaining()
    val frames = totalShorts / maxOf(1, channelCount)

    val mono = FloatArray(frames)
    for (f in 0 until frames) {
        var sum = 0f
        for (c in 0 until channelCount) {
            sum += shortBuf.get(f * channelCount + c) / 32768.0f
        }
        mono[f] = sum / channelCount
    }

    if (sampleRate == 16000) return mono
    val ratio = sampleRate.toDouble() / 16000.0
    val outLen = (mono.size / ratio).toInt()
    val resampled = FloatArray(outLen)
    for (i in 0 until outLen) {
        val srcPos = i * ratio
        val idx0 = srcPos.toInt().coerceIn(0, mono.size - 1)
        val idx1 = (idx0 + 1).coerceIn(0, mono.size - 1)
        val frac = (srcPos - idx0).toFloat()
        resampled[i] = mono[idx0] * (1f - frac) + mono[idx1] * frac
    }
    return resampled
}

private fun loadHistory(prefs: android.content.SharedPreferences): List<SongSuggestion> {
    val raw = prefs.getString("song_history_json", "[]") ?: "[]"
    return try {
        val arr = JSONArray(raw)
        val out = mutableListOf<SongSuggestion>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(
                SongSuggestion(
                    artist = obj.optString("artist", ""),
                    title = obj.optString("title", "")
                )
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveHistory(prefs: android.content.SharedPreferences, items: List<SongSuggestion>) {
    val arr = JSONArray()
    for (item in items) {
        val obj = JSONObject()
        obj.put("artist", item.artist)
        obj.put("title", item.title)
        arr.put(obj)
    }
    prefs.edit().putString("song_history_json", arr.toString()).apply()
}

private suspend fun searchLrclibSuggestions(query: String): List<SongSuggestion> = withContext(Dispatchers.IO) {
    var conn: HttpURLConnection? = null
    try {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = URL("https://lrclib.net/api/search?q=$encoded")
        conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", "LyricScan-Android/1.0")
        }
        if (conn.responseCode == 200) {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            val result = mutableListOf<SongSuggestion>()
            for (i in 0 until minOf(arr.length(), 15)) {
                val obj = arr.getJSONObject(i)
                val a = obj.optString("artistName", "").trim()
                val t = obj.optString("trackName", "").trim()
                if (a.isNotEmpty() || t.isNotEmpty()) {
                    result.add(SongSuggestion(artist = a, title = t))
                }
            }
            return@withContext result
        }
        emptyList()
    } catch (_: Exception) {
        emptyList()
    } finally {
        conn?.disconnect()
    }
}

private suspend fun fetchLrclibLyrics(artist: String, title: String): String? = withContext(Dispatchers.IO) {
    val query = listOf(artist.trim(), title.trim()).filter { it.isNotEmpty() }.joinToString(" ")
    if (query.isEmpty()) return@withContext null

    var conn: HttpURLConnection? = null
    try {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://lrclib.net/api/search?q=$encoded")
        conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "LyricScan-Android/1.0")
        }
        if (conn.responseCode == 200) {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val synced = obj.optString("syncedLyrics", "").trim()
                val plain = obj.optString("plainLyrics", "").trim()
                if (synced.isNotEmpty()) return@withContext synced
                if (plain.isNotEmpty()) return@withContext plain
            }
        }
        null
    } catch (_: Exception) {
        null
    } finally {
        conn?.disconnect()
    }
}
