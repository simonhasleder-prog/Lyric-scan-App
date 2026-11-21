package com.lyricscan.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
    var whisperApiKey by remember { mutableStateOf(prefs.getString("whisper_api_key", "") ?: "") }

    // Lokale Historie für sofortige Schreibhilfe ohne Netz
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
                        status = "Suche Songtext..."
                        val result = fetchLrclibLyrics(artist, title)
                        if (result != null) {
                            lyrics = result
                            status = "Songtext gefunden."
                        } else {
                            status = "Kein Songtext gefunden. Prüfe Interpret und Titel oder nutze Whisper."
                        }
                        isLoading = false
                    }
                },
                enabled = !isLoading && (artist.isNotBlank() || title.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isLoading) "Suche läuft..." else "Songtext suchen")
            }

            // Whisper-Transkription für ausgewählte Audiodatei
            OutlinedTextField(
                value = whisperApiKey,
                onValueChange = {
                    whisperApiKey = it
                    prefs.edit().putString("whisper_api_key", it.trim()).apply()
                },
                label = { Text("Whisper API-Key (sk-...)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    val uri = selectedAudioUri
                    val key = whisperApiKey.trim()
                    if (uri == null) {
                        status = "Bitte zuerst oben eine Audiodatei auswählen."
                        return@Button
                    }
                    if (key.isEmpty()) {
                        status = "Bitte zuerst deinen Whisper API-Key eingeben."
                        return@Button
                    }
                    prefs.edit().putString("whisper_api_key", key).apply()
                    scope.launch {
                        isLoading = true
                        status = "Transkribiere Audio mit Whisper..."
                        val fileNameToSend = if (selectedAudioName.isNotBlank()) selectedAudioName else "audio.mp3"
                        val transcriptResult = transcribeAudioWithWhisper(context, uri, fileNameToSend, key)
                        transcriptResult.onSuccess { text ->
                            lyrics = text
                            recordPair(artist, title)
                            status = "Whisper-Transkription abgeschlossen."
                        }.onFailure { err ->
                            status = "Whisper-Fehler: ${err.message ?: "Unbekannter Fehler"}"
                        }
                        isLoading = false
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isLoading) "Bitte warten..." else "Per Whisper transkribieren")
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
                    Text("Songtext kopieren")
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

private suspend fun transcribeAudioWithWhisper(
    context: Context,
    uri: Uri,
    fileName: String,
    apiKey: String
): Result<String> = withContext(Dispatchers.IO) {
    var conn: HttpURLConnection? = null
    try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext Result.failure(Exception("Audiodatei konnte nicht gelesen werden."))

        if (bytes.size > 25 * 1024 * 1024) {
            return@withContext Result.failure(Exception("Datei ist größer als das 25-MB-Limit der Whisper API."))
        }

        val boundary = "----LyricScanBoundary${System.currentTimeMillis()}"
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val bodyBuffer = ByteArrayOutputStream()
        DataOutputStream(bodyBuffer).apply {
            writeBytes(twoHyphens + boundary + lineEnd)
            writeBytes("Content-Disposition: form-data; name=\"model\"$lineEnd$lineEnd")
            writeBytes("whisper-1$lineEnd")

            writeBytes(twoHyphens + boundary + lineEnd)
            writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"$lineEnd")
            writeBytes("Content-Type: audio/mpeg$lineEnd$lineEnd")
            write(bytes)
            writeBytes(lineEnd)

            writeBytes(twoHyphens + boundary + twoHyphens + lineEnd)
            flush()
        }

        val payload = bodyBuffer.toByteArray()
        val url = URL("https://api.openai.com/v1/audio/transcriptions")
        conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 60000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(payload.size)
        }

        conn.outputStream.use { out ->
            out.write(payload)
            out.flush()
        }

        val code = conn.responseCode
        val responseText = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
        }

        if (code !in 200..299) {
            val errMsg = try {
                JSONObject(responseText).optJSONObject("error")?.optString("message") ?: responseText
            } catch (_: Exception) {
                responseText
            }
            return@withContext Result.failure(Exception(errMsg))
        }

        val transcript = JSONObject(responseText).optString("text", "").trim()
        if (transcript.isEmpty()) {
            Result.failure(Exception("Whisper hat keinen Text zurückgegeben."))
        } else {
            Result.success(transcript)
        }
    } catch (e: Exception) {
        Result.failure(e)
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
