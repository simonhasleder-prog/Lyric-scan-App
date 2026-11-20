package com.lyricscan.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class AudioTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val folderName: String,
    val displayName: String,
    val durationMs: Long
)

data class LyricCandidate(
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSec: Double,
    val plainLyrics: String,
    val syncedLyrics: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LyricScanTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF070A12)
                ) {
                    LyricScanApp()
                }
            }
        }
    }
}

@Composable
fun LyricScanTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFF6366F1),
        secondary = Color(0xFF10B981),
        background = Color(0xFF070A12),
        surface = Color(0xFF0D1220),
        onPrimary = Color.White,
        onBackground = Color(0xFFF1F5F9),
        onSurface = Color(0xFFF1F5F9)
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricScanApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    var allTracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf("Alle Ordner") }

    var currentTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var manualArtistInput by remember { mutableStateOf("") }
    var manualTitleInput by remember { mutableStateOf("") }

    var isSearchingLyrics by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Wähle einen Song aus deiner gesamten Musik-Bibliothek.") }
    var candidates by remember { mutableStateOf<List<LyricCandidate>>(emptyList()) }
    var lyricsText by remember { mutableStateOf("") }
    var preferSynced by remember { mutableStateOf(true) }
    var isSyncView by remember { mutableStateOf(false) }
    var syncJumpMode by remember { mutableStateOf(false) }

    // Audio-Player Zustand
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosMs by remember { mutableLongStateOf(0L) }
    var totalDurMs by remember { mutableLongStateOf(0L) }

    // Berechtigung anfordern
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            scope.launch {
                isScanning = true
                allTracks = scanAllDeviceAudio(context)
                isScanning = false
            }
        }
    }

    // Manueller System-Dateipicker als Backup für beliebige Dateien
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val info = extractTrackFromUri(context, uri)
            currentTrack = info
            manualArtistInput = if (info.artist == "Unbekannter Interpret") "" else info.artist
            manualTitleInput = info.title
            candidates = emptyList()
            scope.launch {
                isSearchingLyrics = true
                statusMessage = "Suche Songtext für „${info.title}“..."
                val found = searchUniversalLyrics(manualArtistInput, manualTitleInput, info.durationMs / 1000.0)
                candidates = found
                if (found.isNotEmpty()) {
                    val best = found.first()
                    lyricsText = pickBestLyricFormat(best, preferSynced)
                    statusMessage = "Treffer: ${best.artistName} – ${best.trackName} (${found.size} Versionen)"
                } else {
                    statusMessage = "Kein direkter Treffer. Passe oben Interpret/Titel an und tippe auf „Suchen“."
                }
                isSearchingLyrics = false
            }
        }
    }

    // Initialer Scan aller MP3s auf dem gesamten Gerät
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            isScanning = true
            allTracks = scanAllDeviceAudio(context)
            isScanning = false
        }
    }

    // Audio-Player starten/wechseln bei neuem Song
    DisposableEffect(currentTrack) {
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        currentPosMs = 0L
        totalDurMs = currentTrack?.durationMs ?: 0L

        val track = currentTrack
        if (track != null) {
            try {
                val mp = MediaPlayer().apply {
                    setDataSource(context, track.uri)
                    prepare()
                    setOnCompletionListener {
                        isPlaying = false
                    }
                }
                mediaPlayer = mp
                totalDurMs = mp.duration.toLong().coerceAtLeast(1L)
            } catch (e: Exception) {
                Toast.makeText(context, "Audio konnte nicht geladen werden: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    // Live-Zeit-Update während der Wiedergabe
    LaunchedEffect(isPlaying, mediaPlayer) {
        while (isPlaying && mediaPlayer != null) {
            try {
                currentPosMs = mediaPlayer?.currentPosition?.toLong() ?: 0L
            } catch (_: Exception) {
            }
            delay(200)
        }
    }

    val folders = remember(allTracks) {
        listOf("Alle Ordner") + allTracks.map { it.folderName }.distinct().sorted()
    }

    val filteredTracks = remember(allTracks, searchQuery, selectedFolder) {
        allTracks.filter { track ->
            val matchesFolder = selectedFolder == "Alle Ordner" || track.folderName == selectedFolder
            val q = searchQuery.trim().lowercase(Locale.getDefault())
            val matchesQuery = q.isEmpty() ||
                track.title.lowercase(Locale.getDefault()).contains(q) ||
                track.artist.lowercase(Locale.getDefault()).contains(q) ||
                track.displayName.lowercase(Locale.getDefault()).contains(q) ||
                track.folderName.lowercase(Locale.getDefault()).contains(q)
            matchesFolder && matchesQuery
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LyricScan", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                        Text(
                            "${allTracks.size} Audiodateien auf dem gesamten Gerät",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        filePickerLauncher.launch(arrayOf("audio/*"))
                    }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Datei manuell öffnen", tint = Color(0xFF818CF8))
                    }
                    IconButton(onClick = {
                        if (hasPermission) {
                            scope.launch {
                                isScanning = true
                                allTracks = scanAllDeviceAudio(context)
                                isScanning = false
                                Toast.makeText(context, "${allTracks.size} Songs aktualisiert", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            permissionLauncher.launch(requiredPermission)
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Neu scannen", tint = Color(0xFF10B981))
                    }
                },
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
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. BERECHTIGUNGS-KARTE FALLS NOCH NICHT ERTEILT
            if (!hasPermission) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Zugriff auf alle MP3-Dateien erlauben",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 15.sp
                        )
                        Text(
                            "Damit LyricScan alle Ordner (Music, Downloads, Unterordner & SD-Karte) automatisch findet, erlaube bitte den Audio-Zugriff.",
                            fontSize = 12.sp,
                            color = Color(0xFFCBD5E1)
                        )
                        Button(
                            onClick = { permissionLauncher.launch(requiredPermission) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                        ) {
                            Text("Jetzt alle MP3-Ordner freigeben", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 2. MUSIK-BIBLIOTHEK (ALLE ORDNER GERÄTEWEIT)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1220)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "1. SONG WÄHLEN (${filteredTracks.size})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF818CF8)
                        )
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Nach Song, Interpret oder Ordner suchen...", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Löschen")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Ordner-Chips (alle Ordner auf dem Smartphone)
                    if (folders.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(folders) { folder ->
                                FilterChip(
                                    selected = selectedFolder == folder,
                                    onClick = { selectedFolder = folder },
                                    label = { Text(folder, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    // Song-Liste (kompakt & scrollbar)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 230.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF070A12))
                    ) {
                        if (filteredTracks.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (hasPermission) "Keine passenden Audiodateien gefunden." else "Bitte oben Zugriff erlauben oder rechts oben das Ordner-Symbol nutzen.",
                                    fontSize = 12.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(filteredTracks, key = { it.id }) { track ->
                                    val isSelected = currentTrack?.id == track.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (isSelected) Color(0xFF1E1B4B) else Color.Transparent)
                                            .clickable {
                                                currentTrack = track
                                                manualArtistInput = if (track.artist == "Unbekannter Interpret") "" else track.artist
                                                manualTitleInput = track.title
                                                candidates = emptyList()
                                                isSyncView = false
                                                scope.launch {
                                                    isSearchingLyrics = true
                                                    statusMessage = "Suche Songtext für „${track.title}“..."
                                                    val found = searchUniversalLyrics(
                                                        manualArtistInput,
                                                        manualTitleInput,
                                                        track.durationMs / 1000.0
                                                    )
                                                    candidates = found
                                                    if (found.isNotEmpty()) {
                                                        val best = found.first()
                                                        lyricsText = pickBestLyricFormat(best, preferSynced)
                                                        statusMessage = "Gefunden: ${best.artistName} – ${best.trackName}"
                                                    } else {
                                                        statusMessage = "Kein automatischer Treffer – passe Interpret/Titel unten an."
                                                    }
                                                    isSearchingLyrics = false
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = if (isSelected) Color(0xFF10B981) else Color(0xFF6366F1),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                track.title,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = Color.White
                                            )
                                            Text(
                                                "${track.artist} • 📁 ${track.folderName}",
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                        Text(
                                            formatMs(track.durationMs),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                    HorizontalDivider(color = Color(0xFF131A2E))
                                }
                            }
                        }
                    }
                }
            }

            // 3. AUDIO-PLAYER FÜR DEN GEWÄHLTEN SONG
            if (currentTrack != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131A2E)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.border(1.dp, Color(0xFF10B981).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilledIconButton(
                                onClick = {
                                    val mp = mediaPlayer ?: return@FilledIconButton
                                    if (isPlaying) {
                                        mp.pause()
                                        isPlaying = false
                                    } else {
                                        mp.start()
                                        isPlaying = true
                                    }
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF10B981))
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = Color.Black
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    currentTrack?.title ?: "",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${formatMs(currentPosMs)} / ${formatMs(totalDurMs)}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFA5B4FC)
                                )
                            }
                        }

                        Slider(
                            value = if (totalDurMs > 0) (currentPosMs.toFloat() / totalDurMs.toFloat()).coerceIn(0f, 1f) else 0f,
                            onValueChange = { ratio ->
                                val seekMs = (ratio * totalDurMs).toLong()
                                currentPosMs = seekMs
                                mediaPlayer?.seekTo(seekMs.toInt())
                            }
                        )
                    }
                }
            }

            // 4. UNIVERSELLE SONGTEXT-SUCHE (FÜR ALLE SONGS WELTWEIT)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1220)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "2. UNIVERSELLE SONGTEXT-SUCHE (ALLE INTERPRETEN & SONGS)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF818CF8)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = manualArtistInput,
                            onValueChange = { manualArtistInput = it },
                            label = { Text("Interpret (optional)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = manualTitleInput,
                            onValueChange = { manualTitleInput = it },
                            label = { Text("Songtitel / Suchbegriff", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isSearchingLyrics = true
                                    statusMessage = "Suche weltweit nach „$manualArtistInput $manualTitleInput“..."
                                    val found = searchUniversalLyrics(
                                        manualArtistInput,
                                        manualTitleInput,
                                        (currentTrack?.durationMs ?: 0L) / 1000.0
                                    )
                                    candidates = found
                                    if (found.isNotEmpty()) {
                                        val best = found.first()
                                        lyricsText = pickBestLyricFormat(best, preferSynced)
                                        statusMessage = "Gefunden: ${best.artistName} – ${best.trackName}"
                                    } else {
                                        statusMessage = "Keine Treffer gefunden. Prüfe die Schreibweise des Songtitels."
                                    }
                                    isSearchingLyrics = false
                                }
                            },
                            enabled = !isSearchingLyrics && (manualTitleInput.isNotBlank() || manualArtistInput.isNotBlank()),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isSearchingLyrics) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Songtext suchen", fontWeight = FontWeight.Bold)
                        }

                        FilterChip(
                            selected = preferSynced,
                            onClick = {
                                preferSynced = !preferSynced
                                if (candidates.isNotEmpty()) {
                                    lyricsText = pickBestLyricFormat(candidates.first(), preferSynced)
                                }
                            },
                            label = { Text("LRC-Zeitstempel", fontSize = 11.sp) }
                        )
                    }

                    Text(
                        statusMessage,
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )

                    // Alternative Treffer-Auswahl (falls mehrere Versionen wie Live/Album existieren)
                    if (candidates.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(candidates.take(6)) { cand ->
                                AssistChip(
                                    onClick = {
                                        lyricsText = pickBestLyricFormat(cand, preferSynced)
                                        statusMessage = "Ausgewählt: ${cand.artistName} – ${cand.trackName}"
                                    },
                                    label = {
                                        Text(
                                            "${cand.artistName} – ${cand.trackName}",
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 5. SONGTEXT-EDITOR, KLICK-SYNC & EXPORT (.TXT / .LRC)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1220)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "3. SONGTEXT & LRC-SYNC",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(
                                onClick = {
                                    lyricsText = cleanLyricsText(lyricsText)
                                    Toast.makeText(context, "Text bereinigt", Toast.LENGTH_SHORT).show()
                                },
                                label = { Text("Bereinigen", fontSize = 11.sp) }
                            )
                            AssistChip(
                                onClick = { isSyncView = !isSyncView },
                                label = { Text(if (isSyncView) "Editor" else "Klick-Sync", fontSize = 11.sp) }
                            )
                        }
                    }

                    if (!isSyncView) {
                        OutlinedTextField(
                            value = lyricsText,
                            onValueChange = { lyricsText = it },
                            placeholder = { Text("Hier erscheint der gefundene Songtext...", fontSize = 13.sp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 220.dp),
                            shape = RoundedCornerShape(12.dp)
                        )
                    } else {
                        // Klick-Sync-Modus: Zeitstempel setzen oder direkt zur Gesangsstelle springen
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !syncJumpMode,
                                onClick = { syncJumpMode = false },
                                label = { Text("⏱️ Zeitstempel setzen", fontSize = 11.sp) }
                            )
                            FilterChip(
                                selected = syncJumpMode,
                                onClick = { syncJumpMode = true },
                                label = { Text("▶️ Zur Stelle springen", fontSize = 11.sp) }
                            )
                        }

                        val lines = remember(lyricsText) { lyricsText.lines() }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 200.dp, max = 340.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF070A12))
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            lines.forEachIndexed { index, rawLine ->
                                if (rawLine.isNotBlank()) {
                                    val parsed = parseLrcLine(rawLine)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF131A2E))
                                            .clickable {
                                                if (!syncJumpMode || parsed.timestampMs == null) {
                                                    val newStamp = formatLrcTimestamp(currentPosMs)
                                                    val updated = lines.toMutableList()
                                                    updated[index] = "[$newStamp] ${parsed.text}"
                                                    lyricsText = updated.joinToString("\n")
                                                } else {
                                                    mediaPlayer?.seekTo(parsed.timestampMs.toInt())
                                                    if (!isPlaying) {
                                                        mediaPlayer?.start()
                                                        isPlaying = true
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = if (parsed.timestampMs != null) "[${formatLrcTimestamp(parsed.timestampMs)}]" else "[--:--.--]",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = if (parsed.timestampMs != null) Color(0xFF10B981) else Color(0xFF64748B)
                                        )
                                        Text(
                                            text = parsed.text,
                                            fontSize = 13.sp,
                                            color = Color.White,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Export-Buttons: Kopieren, .TXT speichern, .LRC speichern
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (lyricsText.isNotBlank()) {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Songtext", lyricsText))
                                    Toast.makeText(context, "Songtext kopiert!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Kopieren", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                val baseName = sanitizeFileName(manualTitleInput.ifBlank { currentTrack?.title ?: "songtext" })
                                saveTextToDownloads(context, "${baseName}_songtext.txt", lyricsText)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(".TXT", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                val baseName = sanitizeFileName(manualTitleInput.ifBlank { currentTrack?.title ?: "songtext" })
                                saveTextToDownloads(context, "$baseName.lrc", lyricsText)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(".LRC", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// GERÄTEWEITER AUDIO-SCANNER (ALLE ORDNER VIA MEDIASTORE)
// =========================================================================
suspend fun scanAllDeviceAudio(context: Context): List<AudioTrack> = withContext(Dispatchers.IO) {
    val result = mutableListOf<AudioTrack>()
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATA
    )

    val sortOrder = "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"

    try {
        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val displayName = cursor.getString(nameCol) ?: "Unbekannt.mp3"
                val rawTitle = cursor.getString(titleCol) ?: ""
                val rawArtist = cursor.getString(artistCol) ?: ""
                val duration = cursor.getLong(durCol)
                val fullPath = if (dataCol >= 0) cursor.getString(dataCol) else null

                // Nur echte Musikdateien (oder > 5 Sekunden)
                if (duration < 3000L && !displayName.lowercase(Locale.getDefault()).endsWith(".mp3")) continue

                val parsedFromName = parseArtistAndTitleFromFilename(displayName)
                val cleanArtist = if (rawArtist.isBlank() || rawArtist.equals("<unknown>", true)) {
                    parsedFromName.first
                } else {
                    rawArtist.trim()
                }
                val cleanTitle = if (rawTitle.isBlank() || rawTitle == displayName.substringBeforeLast(".")) {
                    parsedFromName.second
                } else {
                    cleanTrackTitle(rawTitle)
                }

                val folderName = fullPath?.let { File(it).parentFile?.name } ?: "Musik"
                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                result.add(
                    AudioTrack(
                        id = id,
                        uri = contentUri,
                        title = cleanTitle,
                        artist = cleanArtist.ifBlank { "Unbekannter Interpret" },
                        folderName = folderName,
                        displayName = displayName,
                        durationMs = duration
                    )
                )
            }
        }
    } catch (_: Exception) {
    }
    result
}

fun extractTrackFromUri(context: Context, uri: Uri): AudioTrack {
    var displayName = "Audiodatei.mp3"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIdx >= 0 && cursor.moveToFirst()) {
            displayName = cursor.getString(nameIdx) ?: displayName
        }
    }
    val parsed = parseArtistAndTitleFromFilename(displayName)
    return AudioTrack(
        id = System.currentTimeMillis(),
        uri = uri,
        title = parsed.second,
        artist = parsed.first.ifBlank { "Unbekannter Interpret" },
        folderName = "Manuell",
        displayName = displayName,
        durationMs = 0L
    )
}

fun parseArtistAndTitleFromFilename(filename: String): Pair<String, String> {
    val withoutExt = filename.substringBeforeLast(".").replace("_", " ").trim()
    val withoutTrackNum = withoutExt.replace(Regex("^\\d{1,3}[.\\s\\-]+"), "").trim()
    return if (withoutTrackNum.contains(" - ")) {
        val parts = withoutTrackNum.split(" - ", limit = 2)
        Pair(parts[0].trim(), cleanTrackTitle(parts[1]))
    } else {
        Pair("", cleanTrackTitle(withoutTrackNum))
    }
}

fun cleanTrackTitle(raw: String): String {
    return raw
        .replace(Regex("\\((?:remaster(?:ed)?|official|audio|video|explicit|clean|hq|hd|lyrics?).*?\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\[(?:remaster(?:ed)?|official|audio|video|explicit|clean|hq|hd|lyrics?).*?\\]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}

// =========================================================================
// UNIVERSELLE SONGTEXT-SUCHE (ALLE SONGS & INTERPRETEN WELTWEIT)
// =========================================================================
suspend fun searchUniversalLyrics(
    artist: String,
    title: String,
    durationSec: Double
): List<LyricCandidate> = withContext(Dispatchers.IO) {
    val results = mutableListOf<LyricCandidate>()
    val cleanArtist = artist.trim()
    val cleanTitle = cleanTrackTitle(title)

    try {
        // 1. Direkter Treffer (wenn Interpret & Titel bekannt sind)
        if (cleanArtist.isNotEmpty() && cleanTitle.isNotEmpty()) {
            val encodedArtist = URLEncoder.encode(cleanArtist, "UTF-8")
            val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
            val getUrl = "https://lrclib.net/api/get?artist_name=$encodedArtist&track_name=$encodedTitle"
            val singleJson = httpGetString(getUrl)
            if (singleJson != null) {
                parseLyricJsonObject(JSONObject(singleJson))?.let { results.add(it) }
            }
        }

        // 2. Universelle Volltextsuche für beliebige Songs, Schreibweisen oder nur Titel
        val query = listOf(cleanArtist, cleanTitle).filter { it.isNotEmpty() }.joinToString(" ")
        if (query.isNotEmpty()) {
            val encodedQ = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://lrclib.net/api/search?q=$encodedQ"
            val arrayJson = httpGetString(searchUrl)
            if (arrayJson != null) {
                val arr = JSONArray(arrayJson)
                for (i in 0 until arr.length()) {
                    val item = parseLyricJsonObject(arr.getJSONObject(i))
                    if (item != null && results.none {
                            it.trackName.equals(item.trackName, true) &&
                                it.artistName.equals(item.artistName, true) &&
                                it.syncedLyrics == item.syncedLyrics
                        }
                    ) {
                        results.add(item)
                    }
                }
            }
        }
    } catch (_: Exception) {
    }

    // Sortieren: Passende Songlänge und vorhandene LRC-Zeitstempel nach oben
    if (durationSec > 10.0) {
        results.sortedWith(
            compareBy<LyricCandidate> { kotlin.math.abs(it.durationSec - durationSec) > 8.0 }
                .thenByDescending { it.syncedLyrics.isNotBlank() }
        )
    } else {
        results.sortedByDescending { it.syncedLyrics.isNotBlank() }
    }
}

private fun httpGetString(urlString: String): String? {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "LyricScan-Android/1.0")
        }
        if (conn.responseCode == 200) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    } finally {
        conn?.disconnect()
    }
}

private fun parseLyricJsonObject(obj: JSONObject): LyricCandidate? {
    val plain = obj.optString("plainLyrics", "").trim()
    val synced = obj.optString("syncedLyrics", "").trim()
    if (plain.isEmpty() && synced.isEmpty()) return null
    return LyricCandidate(
        trackName = obj.optString("trackName", "Unbekannter Titel"),
        artistName = obj.optString("artistName", "Unbekannter Interpret"),
        albumName = obj.optString("albumName", ""),
        durationSec = obj.optDouble("duration", 0.0),
        plainLyrics = plain,
        syncedLyrics = synced
    )
}

fun pickBestLyricFormat(candidate: LyricCandidate, preferSynced: Boolean): String {
    return if (preferSynced && candidate.syncedLyrics.isNotBlank()) {
        candidate.syncedLyrics
    } else {
        candidate.plainLyrics.ifBlank { candidate.syncedLyrics }
    }
}

// =========================================================================
// TEXT-BEREINIGUNG, LRC-HELPER & DOWNLOAD-EXPORT
// =========================================================================
fun cleanLyricsText(raw: String): String {
    val cleanedLines = mutableListOf<String>()
    var lastNorm = ""
    var repeatCount = 0

    for (line in raw.lines()) {
        val noTags = line
            .replace(Regex("[\\[\\(]\\s*(?:music|instrumental|applause|singing|guitar solo|beat).*?[\\]\\)]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[♪♫♬♩]+"), "")
            .trim()
        if (noTags.isEmpty()) continue
        val norm = noTags.lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9äöüß\\s]"), "").trim()
        if (norm.isEmpty()) continue
        if (norm == lastNorm) {
            repeatCount++
            if (repeatCount <= 2) cleanedLines.add(noTags)
        } else {
            lastNorm = norm
            repeatCount = 1
            cleanedLines.add(noTags)
        }
    }
    return cleanedLines.joinToString("\n")
}

data class ParsedLrc(val timestampMs: Long?, val text: String)

fun parseLrcLine(line: String): ParsedLrc {
    val match = Regex("^\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,2}))?\\]\\s*(.*)$").find(line.trim())
    return if (match != null) {
        val min = match.groupValues[1].toLongOrNull() ?: 0L
        val sec = match.groupValues[2].toLongOrNull() ?: 0L
        val hun = match.groupValues[3].padEnd(2, '0').toLongOrNull() ?: 0L
        val ms = (min * 60 + sec) * 1000L + hun * 10L
        ParsedLrc(ms, match.groupValues[4])
    } else {
        ParsedLrc(null, line.trim())
    }
}

fun formatLrcTimestamp(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val mins = (safe / 1000L) / 60L
    val secs = (safe / 1000L) % 60L
    val hundredths = (safe % 1000L) / 10L
    return String.format(Locale.US, "%02d:%02d.%02d", mins, secs, hundredths)
}

fun formatMs(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val mins = (safe / 1000L) / 60L
    val secs = (safe / 1000L) % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

fun sanitizeFileName(name: String): String {
    return name.replace(Regex("[^a-zA-Z0-9äöüÄÖÜß._\\- ]"), "_").trim().ifEmpty { "songtext" }
}

fun saveTextToDownloads(context: Context, fileName: String, content: String) {
    if (content.isBlank()) {
        Toast.makeText(context, "Kein Songtext zum Speichern vorhanden", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Gespeichert in Downloads: $fileName", Toast.LENGTH_LONG).show()
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, fileName)
            file.writeText(content, Charsets.UTF_8)
            Toast.makeText(context, "Gespeichert: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Fehler beim Speichern: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
