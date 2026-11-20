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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

    var artist by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var lyrics by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Bereit. Wähle eine Audiodatei oder suche nach Interpret & Titel.") }
    var isLoading by remember { mutableStateOf(false) }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
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
                onValueChange = { artist = it },
                label = { Text("Interpret") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Songtitel") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        status = "Suche Songtext..."
                        val result = fetchLrclibLyrics(artist, title)
                        if (result != null) {
                            lyrics = result
                            status = "Songtext gefunden."
                        } else {
                            status = "Kein Songtext gefunden. Prüfe Interpret und Titel."
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

            Text(text = status, fontSize = 13.sp, color = Color(0xFF94A3B8))

            OutlinedTextField(
                value = lyrics,
                onValueChange = { lyrics = it },
                label = { Text("Songtext") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp)
            )

            OutlinedButton(
                onClick = {
                    if (lyrics.isNotBlank()) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Songtext", lyrics))
                        Toast.makeText(context, "Kopiert", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Songtext kopieren")
            }
        }
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
