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
    var selectedAudioName by remember { mutableStateOf("") }

    val modelFile = remember { File(context.filesDir, "whisper_small_int8_cpu_ort_1.18.0.onnx") }
    var isModelReady by remember { mutableStateOf(modelFile.exists() && modelFile.length() > 50_000_000L) }

    // Lokale Historie für sofortige Schreibhilfe
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
            selectedAudioName = fileName
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

            // Interpret-Feld + Schreibhilfe
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

            // Songtitel-Feld + Schreibhilfe (gefiltert nach gewähltem Interpreten)
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

            // Offline Whisper (small)
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
                            status = "Lade Whisper-Small Modell einmalig herunter (ca. 260 MB)..."
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
        val sessionOptions = OrtSession.SessionOptions().apply {
            registerCustomOpLibrary(OrtxPackage.getLibraryPath())
        }

        sessionOptions.use { opts ->
            env.createSession(modelFile.absolutePath, opts).use { session ->
                // Whisper verarbeitet Segmente von max. 30 Sekunden (480.000 Samples bei 16 kHz)
                val chunkSamples = 16000 * 30
                val fullTranscript = StringBuilder()
                var offset = 0

                while (offset < pcmSamples.size) {
                    val end = minOf(offset + chunkSamples, pcmSamples.size)
                    val chunk = pcmSamples
