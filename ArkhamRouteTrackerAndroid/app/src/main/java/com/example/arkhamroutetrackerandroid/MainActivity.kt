package com.example.arkhamroutetrackerandroid

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val GITHUB_URL = "https://github.com/endfreezy/Mobile-Arkham-Route-Tracker"
private const val GITHUB_OWNER = "endfreezy"
private const val GITHUB_REPO = "Mobile-Arkham-Route-Tracker"

private const val PREFS_NAME = "arkham_tracker_prefs"
private const val KEY_SAVED_HOST = "saved_host_ip_port"
private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

data class RouteEntryUi(
    val id: Int,
    val title: String,
    val checked: Boolean
)

data class LiveTrackerUiState(
    val routeName: String = "",
    val progressText: String = "",
    val riddleCountText: String = "",
    val prisonersText: String = "",
    val entries: List<RouteEntryUi> = emptyList(),
    val remainingPrisoners: List<String> = emptyList(),
    val errorMessage: String? = null
)

data class ReleaseUpdateInfo(
    val tagName: String,
    val notes: String,
    val apkUrl: String,
    val htmlUrl: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(android.graphics.Color.parseColor("#14161A"))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ArkhamLiveScreen()
            }
        }
    }
}

private fun normalizePrisoners(rawList: List<String>): List<String> {
    return rawList
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

private fun buildStateUrl(rawHost: String): String {
    val cleaned = rawHost.trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .removeSuffix("/state")
        .trimEnd('/')
    return "http://$cleaned/state"
}

private fun getCurrentAppVersionName(context: Context): String {
    return try {
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        pkgInfo.versionName ?: "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
    }
}

private fun parseVersionParts(raw: String): List<Int> {
    val clean = raw.trim().removePrefix("v").removePrefix("V").substringBefore("-")
    return clean.split(".").map { part ->
        part.filter { it.isDigit() }.toIntOrNull() ?: 0
    }
}

private fun isRemoteVersionNewer(remoteTag: String, currentVersion: String): Boolean {
    val remote = parseVersionParts(remoteTag)
    val current = parseVersionParts(currentVersion)
    val maxLen = maxOf(remote.size, current.size)
    for (i in 0 until maxLen) {
        val r = remote.getOrElse(i) { 0 }
        val c = current.getOrElse(i) { 0 }
        if (r > c) return true
        if (r < c) return false
    }
    return false
}

private suspend fun checkLatestGitHubRelease(currentVersion: String): ReleaseUpdateInfo? =
    withContext(Dispatchers.IO) {
        try {
            val apiUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "ArkhamTracker-Android")
                connectTimeout = 4000
                readTimeout = 4000
            }
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
            conn.disconnect()

            if (code !in 200..299 || body.isBlank()) return@withContext null

            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "").trim()
            val htmlUrl = json.optString("html_url", "").trim()
            val notes = json.optString("body", "").trim()

            if (tagName.isBlank() || !isRemoteVersionNewer(tagName, currentVersion)) {
                return@withContext null
            }

            var apkUrl = ""
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    val downloadUrl = asset.optString("browser_download_url", "")
                    if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                        apkUrl = downloadUrl
                        break
                    }
                }
            }

            ReleaseUpdateInfo(
                tagName = tagName,
                notes = notes,
                apkUrl = apkUrl,
                htmlUrl = htmlUrl
            )
        } catch (_: Exception) {
            null
        }
    }

private fun installApkFile(context: Context, apkFile: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun startApkDownload(
    context: Context,
    apkUrl: String,
    fileName: String,
    onStatusChange: (String) -> Unit
) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            onStatusChange("Enable 'Install unknown apps' permission, then tap Update again.")
            val settingsIntent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            return
        }

        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val targetFile = File(downloadsDir, fileName)
        if (targetFile.exists()) {
            targetFile.delete()
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("ArkhamTracker $fileName")
            .setDescription("Downloading update...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(targetFile))

        val downloadId = downloadManager.enqueue(request)
        onStatusChange("Downloading update...")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val receivedId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (receivedId == downloadId) {
                    try {
                        ctx?.unregisterReceiver(this)
                    } catch (_: Exception) {
                    }
                    if (targetFile.exists()) {
                        onStatusChange("Opening installer...")
                        installApkFile(context, targetFile)
                    } else {
                        onStatusChange("Downloaded file not found.")
                    }
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    } catch (e: Exception) {
        onStatusChange("Download failed: ${e.message ?: "error"}")
    }
}

@Composable
private fun KeepScreenOnEffect(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val window = (context as? Activity)?.window
        if (enabled && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
fun ArkhamLiveScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val currentVersion = remember { getCurrentAppVersionName(context) }

    val initialHost = remember {
        prefs.getString(KEY_SAVED_HOST, "192.168.1.50:8765") ?: "192.168.1.50:8765"
    }
    val initialKeepScreenOn = remember {
        prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
    }

    var hostInput by remember { mutableStateOf(initialHost) }
    var activeUrl by remember { mutableStateOf(buildStateUrl(initialHost)) }
    var isConnected by remember { mutableStateOf(false) }
    var keepScreenOn by remember { mutableStateOf(initialKeepScreenOn) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<ReleaseUpdateInfo?>(null) }
    var updateStatusText by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf(LiveTrackerUiState()) }

    fun saveAndApplyHost() {
        val cleaned = hostInput.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removeSuffix("/state")
            .trimEnd('/')
        if (cleaned.isNotEmpty()) {
            hostInput = cleaned
            prefs.edit().putString(KEY_SAVED_HOST, cleaned).apply()
            activeUrl = buildStateUrl(cleaned)
        }
    }

    KeepScreenOnEffect(enabled = keepScreenOn)

    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    var linkStatusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val found = checkLatestGitHubRelease(currentVersion)
        if (found != null) {
            updateInfo = found
        }
    }

    LaunchedEffect(isConnected, activeUrl) {
        while (isConnected) {
            state = fetchTrackerState(activeUrl)
            delay(1000L)
        }
    }

    val entriesToShow = remember(state.entries) {
        state.entries.sortedWith(
            compareByDescending<RouteEntryUi> { it.checked }
                .thenBy { it.id }
        )
    }

    val nextUncheckedIndex = remember(entriesToShow) {
        entriesToShow.indexOfFirst { !it.checked }
    }

    val prisonersToShow = remember(state.remainingPrisoners) {
        normalizePrisoners(state.remainingPrisoners)
    }

    val entriesListState = rememberLazyListState()
    var previousCheckedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var hasInitializedEntries by remember { mutableStateOf(false) }

    LaunchedEffect(entriesToShow, nextUncheckedIndex) {
        val currentCheckedIds = entriesToShow.filter { it.checked }.map { it.id }.toSet()
        if (!hasInitializedEntries) {
            previousCheckedIds = currentCheckedIds
            hasInitializedEntries = true
            return@LaunchedEffect
        }

        val newlyCheckedIds = currentCheckedIds - previousCheckedIds
        if (newlyCheckedIds.isNotEmpty() && nextUncheckedIndex != -1) {
            entriesListState.animateScrollToItem(nextUncheckedIndex)
        }
        previousCheckedIds = currentCheckedIds
    }

    if (updateInfo != null) {
        val info = updateInfo!!
        AlertDialog(
            onDismissRequest = {
                updateInfo = null
                updateStatusText = null
            },
            title = {
                Text(
                    text = "Update Available (${info.tagName})",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Column {
                    Text(
                        text = "Current version: $currentVersion",
                        color = Color(0xFFB8C0CC),
                        fontSize = 12.sp
                    )
                    if (info.notes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = info.notes.take(300),
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                    if (updateStatusText != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = updateStatusText!!,
                            color = Color(0xFF58A6FF),
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (info.apkUrl.isNotBlank()) {
                            startApkDownload(
                                context = context,
                                apkUrl = info.apkUrl,
                                fileName = "ArkhamTracker-${info.tagName}.apk",
                                onStatusChange = { updateStatusText = it }
                            )
                        } else if (info.htmlUrl.isNotBlank()) {
                            uriHandler.openUri(info.htmlUrl)
                        }
                    }
                ) {
                    Text(if (info.apkUrl.isNotBlank()) "Update" else "Open Release")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        updateInfo = null
                        updateStatusText = null
                    }
                ) {
                    Text("Later")
                }
            },
            containerColor = Color(0xFF1E2229)
        )
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = {
                Text(
                    text = "Settings",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Keep Screen On",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Prevent screen sleep while tracking",
                            color = Color(0xFF8B949E),
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = keepScreenOn,
                        onCheckedChange = { checked ->
                            keepScreenOn = checked
                            prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, checked).apply()
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Close")
                }
            },
            containerColor = Color(0xFF1E2229)
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = {
                showAboutDialog = false
                linkStatusMessage = null
            },
            title = {
                Text(
                    text = "About",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Column {
                    Text(
                        text = "made by ShikenNuggets",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Android - Web port made by Freezy",
                        color = Color(0xFFB8C0CC),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = GITHUB_URL,
                        color = Color(0xFF58A6FF),
                        fontSize = 13.sp,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            try {
                                uriHandler.openUri(GITHUB_URL)
                            } catch (_: Exception) {
                                clipboardManager.setText(AnnotatedString(GITHUB_URL))
                                linkStatusMessage = "Copied link to clipboard"
                            }
                        }
                    )
                    if (linkStatusMessage != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = linkStatusMessage!!,
                            color = Color(0xFF8B949E),
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAboutDialog = false
                        linkStatusMessage = null
                    }
                ) {
                    Text("Close")
                }
            },
            containerColor = Color(0xFF1E2229)
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF14161A))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(10.dp)
    ) {
        val isLandscape = maxWidth > maxHeight

        if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1.55f)
                        .fillMaxHeight()
                ) {
                    TopActionBar(
                        onSettingsClick = { showSettingsDialog = true },
                        onAboutClick = { showAboutDialog = true }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ConnectionBar(
                        hostInput = hostInput,
                        onHostInputChange = {
                            hostInput = it
                            val trimmed = it.trim()
                            if (trimmed.isNotEmpty()) {
                                prefs.edit().putString(KEY_SAVED_HOST, trimmed).apply()
                            }
                        },
                        isConnected = isConnected,
                        onToggleConnect = {
                            if (!isConnected) {
                                saveAndApplyHost()
                                isConnected = true
                            } else {
                                isConnected = false
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    RouteHeaderCard(state = state)
                    ErrorBanner(errorMessage = state.errorMessage)
                    Spacer(modifier = Modifier.height(6.dp))
                    EntriesList(
                        entries = entriesToShow,
                        listState = entriesListState,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                PoliticalPrisonersCard(
                    prisonersToShow = prisonersToShow,
                    prisonersSummary = state.prisonersText,
                    scrollable = true,
                    modifier = Modifier
                        .weight(0.85f)
                        .fillMaxHeight()
                )
            }
        } else {
            val prisonerCount = prisonersToShow.size
            val prisonersWeight = when {
                prisonerCount <= 0 -> 0.13f
                prisonerCount <= 2 -> 0.19f
                prisonerCount <= 5 -> 0.28f
                else -> 0.40f
            }
            val entriesWeight = 1f - prisonersWeight

            Column(modifier = Modifier.fillMaxSize()) {
                TopActionBar(
                    onSettingsClick = { showSettingsDialog = true },
                    onAboutClick = { showAboutDialog = true }
                )
                Spacer(modifier = Modifier.height(6.dp))
                ConnectionBar(
                    hostInput = hostInput,
                    onHostInputChange = {
                        hostInput = it
                        val trimmed = it.trim()
                        if (trimmed.isNotEmpty()) {
                            prefs.edit().putString(KEY_SAVED_HOST, trimmed).apply()
                        }
                    },
                    isConnected = isConnected,
                    onToggleConnect = {
                        if (!isConnected) {
                            saveAndApplyHost()
                            isConnected = true
                        } else {
                            isConnected = false
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                RouteHeaderCard(state = state)
                ErrorBanner(errorMessage = state.errorMessage)
                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .animateContentSize()
                ) {
                    EntriesList(
                        entries = entriesToShow,
                        listState = entriesListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(entriesWeight)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    PoliticalPrisonersCard(
                        prisonersToShow = prisonersToShow,
                        prisonersSummary = state.prisonersText,
                        scrollable = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(prisonersWeight)
                            .heightIn(min = 64.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TopActionBar(
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E2229))
            .border(1.dp, Color(0xFF333944))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "ArkhamTracker",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onSettingsClick,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Settings",
                    fontSize = 12.sp
                )
            }
            OutlinedButton(
                onClick = onAboutClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "About",
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ConnectionBar(
    hostInput: String,
    onHostInputChange: (String) -> Unit,
    isConnected: Boolean,
    onToggleConnect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = hostInput,
            onValueChange = onHostInputChange,
            singleLine = true,
            label = { Text("PC IP:Port") },
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = onToggleConnect) {
            Text(if (isConnected) "Stop" else "Connect")
        }
    }
}

@Composable
private fun RouteHeaderCard(state: LiveTrackerUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E2229))
            .border(1.dp, Color(0xFF333944))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.routeName.ifBlank { "Waiting for Route" },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            if (state.prisonersText.isNotBlank()) {
                Text(
                    text = "Prisoners: ${state.prisonersText}",
                    color = Color(0xFFB8C0CC),
                    fontSize = 12.sp
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = state.progressText.ifBlank { "0%" },
                color = Color(0xFF58A6FF),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = state.riddleCountText,
                color = Color(0xFFD0D7DE),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ErrorBanner(errorMessage: String?) {
    if (errorMessage != null) {
        Text(
            text = errorMessage,
            color = Color(0xFFFF6B6B),
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 6.dp)
        )
    }
}

@Composable
private fun EntriesList(
    entries: List<RouteEntryUi>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier
    ) {
        items(entries, key = { it.id }) { item ->
            RouteEntryRow(item = item)
        }
    }
}

@Composable
private fun RouteEntryRow(item: RouteEntryUi) {
    val rowBg = if (item.checked) Color(0xFF1A241E) else Color(0xFF1B1E24)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(rowBg)
            .border(0.5.dp, Color(0xFF2D333B)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.title,
            color = if (item.checked) Color(0xFF8B949E) else Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        )
        Box(
            modifier = Modifier
                .width(44.dp)
                .fillMaxHeight()
                .border(0.5.dp, Color(0xFF2D333B))
                .background(if (item.checked) Color(0xFF238636) else Color(0xFF21262D)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (item.checked) "✓" else "",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun PoliticalPrisonersCard(
    prisonersToShow: List<String>,
    prisonersSummary: String,
    scrollable: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF1E2229))
            .border(1.dp, Color(0xFF333944))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Political Prisoners (${prisonersToShow.size})",
                color = Color(0xFFFFB86C),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            if (prisonersSummary.isNotBlank()) {
                Text(
                    text = prisonersSummary,
                    color = Color(0xFFB8C0CC),
                    fontSize = 12.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))

        if (prisonersToShow.isEmpty()) {
            Text(
                text = "No political prisoners remaining",
                color = Color(0xFF8B949E),
                fontSize = 12.sp
            )
        } else {
            val itemsColumnModifier = if (scrollable) {
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxWidth()
            }

            Column(modifier = itemsColumnModifier) {
                prisonersToShow.forEach { prisoner ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(Color(0xFFFFB86C))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = prisoner,
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

private suspend fun fetchTrackerState(urlString: String): LiveTrackerUiState = withContext(Dispatchers.IO) {
    try {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2000
            readTimeout = 2000
        }
        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        }
        conn.disconnect()

        if (code !in 200..299) {
            return@withContext LiveTrackerUiState(errorMessage = "HTTP $code")
        }

        val obj = JSONObject(body)
        val entriesArray = obj.optJSONArray("Entries")
        val parsedEntries = mutableListOf<RouteEntryUi>()
        if (entriesArray != null) {
            for (i in 0 until entriesArray.length()) {
                val entryObj = entriesArray.optJSONObject(i) ?: continue
                parsedEntries.add(
                    RouteEntryUi(
                        id = entryObj.optInt("Id", i),
                        title = entryObj.optString("Title", ""),
                        checked = entryObj.optBoolean("Checked", false)
                    )
                )
            }
        }

        val prisonersArray = obj.optJSONArray("RemainingPrisoners")
        val remaining = mutableListOf<String>()
        if (prisonersArray != null) {
            for (i in 0 until prisonersArray.length()) {
                val rawItem = prisonersArray.opt(i)
                val value = when (rawItem) {
                    is JSONObject -> rawItem.optString("Name", rawItem.optString("Title", ""))
                    else -> prisonersArray.optString(i, "")
                }
                if (value.isNotBlank()) {
                    remaining.add(value)
                }
            }
        }

        LiveTrackerUiState(
            routeName = obj.optString("RouteName", ""),
            progressText = obj.optString("ProgressText", ""),
            riddleCountText = obj.optString("RiddleCountText", ""),
            prisonersText = obj.optString("PrisonersText", ""),
            entries = parsedEntries,
            remainingPrisoners = remaining,
            errorMessage = null
        )
    } catch (e: Exception) {
        LiveTrackerUiState(errorMessage = e.message ?: "Connection failed")
    }
}