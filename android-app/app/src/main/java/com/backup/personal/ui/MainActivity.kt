package com.backup.personal.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.backup.personal.data.BackupDatabaseHelper
import com.backup.personal.data.PreferencesManager
import com.backup.personal.network.BackupApiClient
import com.backup.personal.recovery.DeletedMediaScanner
import com.backup.personal.restore.RestoreEngine
import com.backup.personal.service.BackupEngine
import com.backup.personal.service.BackupService
import com.backup.personal.updater.AppUpdater
import com.backup.personal.updater.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Beautiful, soft Pastel White, Light Mint Green, and Delicate Blush Pink theme.
 * High readability, soothing pastel tones, and clean modern card layout.
 */
object AppPalette {
    val CanvasBackground = Color(0xFFFAFCFA) // Crisp luminous surface with subtle warm-mint air
    val CardBackground = Color(0xFFFFFFFF)   // Pure white card surface
    val CardBorder = Color(0xFFEBF1EE)       // Soft pastel border
    val TextMain = Color(0xFF1E2923)         // Soft obsidian slate for headings
    val TextSub = Color(0xFF64746D)          // Slate-sage for secondary descriptions
    val TextMuted = Color(0xFF94A39B)        // Muted label text

    // Lighter Soft Pastel Mint Green Accents
    val MintHero = Color(0xFF10B981)         // Lighter emerald / mint
    val MintLight = Color(0xFF34D399)        // Soft bright pastel mint
    val MintSurface = Color(0xFFF0FDF4)      // Soft airy mint wash
    val MintBorder = Color(0xFFBBF7D0)       // Delicate pastel mint border
    val MintText = Color(0xFF15803D)         // Soft legible deep-mint

    // Lighter Soft Pastel Blush Pink Accents
    val BlushHero = Color(0xFFF43F5E)        // Soft rose-red (gentle when offline)
    val BlushLight = Color(0xFFFDA4AF)       // Delicate pastel blossom
    val BlushSurface = Color(0xFFFFF1F2)     // Soft airy blush wash
    val BlushBorder = Color(0xFFFECDD3)      // Delicate pastel blush border
    val BlushText = Color(0xFFBE185D)        // Soft legible rose

    // Gradients
    val MintHeroGradient = Brush.horizontalGradient(listOf(MintLight, MintHero))
    val BlushCardGradient = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), BlushSurface))
    val MintCardGradient = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), MintSurface))
}

class MainActivity : ComponentActivity() {
    private lateinit var prefs: PreferencesManager
    private lateinit var dbHelper: BackupDatabaseHelper
    private lateinit var engine: BackupEngine
    private lateinit var restoreEngine: RestoreEngine
    private lateinit var recoveryScanner: DeletedMediaScanner
    private lateinit var appUpdater: AppUpdater
    private val apiClient = BackupApiClient()

    private val documentPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            handleManualFileSelection(uri)
        }
    }

    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Permissions handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)
        dbHelper = BackupDatabaseHelper.getInstance(this)
        engine = BackupEngine(this)
        restoreEngine = RestoreEngine(this)
        recoveryScanner = DeletedMediaScanner(this)
        appUpdater = AppUpdater(this)

        requestRequiredPermissions()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = AppPalette.MintHero,
                    secondary = AppPalette.BlushLight,
                    background = AppPalette.CanvasBackground,
                    surface = AppPalette.CardBackground
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppPalette.CanvasBackground
                ) {
                    BackupDashboardScreen()
                }
            }
        }
    }

    private fun getMissingPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }

        return permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        val ungranted = getMissingPermissions()
        if (ungranted.isNotEmpty()) {
            permissionsLauncher.launch(ungranted.toTypedArray())
        }
        prefs.hasRequestedInitialPermissions = true
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun handleManualFileSelection(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            var displayName = "selected_file"
            var size = 0L
            val type = contentResolver.getType(uri) ?: "application/octet-stream"

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) displayName = cursor.getString(nameIndex)
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }

            val staged = engine.stageManualFile(uri, displayName, size, type)
            withContext(Dispatchers.Main) {
                if (staged) {
                    Toast.makeText(this@MainActivity, "Queued: $displayName", Toast.LENGTH_SHORT).show()
                    BackupService.startBackup(this@MainActivity)
                } else {
                    Toast.makeText(this@MainActivity, "Failed to queue file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun BackupDashboardScreen() {
        var isOperating by remember { mutableStateOf(false) }
        var serverConnected by remember { mutableStateOf(false) }
        var serverFreeSpace by remember { mutableStateOf("Unknown") }
        var queueCount by remember { mutableStateOf(0) }
        var verifiedCount by remember { mutableStateOf(0) }
        var currentProgress by remember { mutableStateOf(0f) }
        var currentStatusText by remember { mutableStateOf("Ready — continuous archive protection active") }
        var showSettingsDialog by remember { mutableStateOf(false) }
        var showRecoveryDialog by remember { mutableStateOf(false) }
        var showNewPhoneDialog by remember { mutableStateOf(false) }
        var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
        var isDownloadingUpdate by remember { mutableStateOf(false) }
        var updateDownloadProgress by remember { mutableStateOf(0f) }
        var missingPermissionsCount by remember { mutableStateOf(getMissingPermissions().size) }

        // Check for updates on startup
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val info = appUpdater.checkForUpdates()
                withContext(Dispatchers.Main) {
                    if (info.hasUpdate) {
                        updateInfo = info
                    }
                }
            }
        }

        // Periodic state polling
        LaunchedEffect(Unit) {
            while (true) {
                withContext(Dispatchers.IO) {
                    val health = apiClient.checkHealth(prefs.serverHost, prefs.serverPort)
                    serverConnected = health != null
                    serverFreeSpace = if (health != null) {
                        "${health.freeSpaceBytes / (1024 * 1024 * 1024)} GB free"
                    } else "Offline"

                    val stats = dbHelper.getStats()
                    val queued = (stats["QUEUED"] ?: 0) + (stats["UPLOADING"] ?: 0) + (stats["DISCOVERED"] ?: 0)
                    val verified = stats["VERIFIED"] ?: 0

                    withContext(Dispatchers.Main) {
                        queueCount = queued
                        verifiedCount = verified
                    }
                }
                delay(3000)
            }
        }

        Scaffold(
            containerColor = AppPalette.CanvasBackground,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            // Glowing dynamic status dot: GREEN when online, RED when offline
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(if (serverConnected) AppPalette.MintBorder else AppPalette.BlushBorder),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (serverConnected) AppPalette.MintHero else AppPalette.BlushHero)
                                )
                            }
                            Text(
                                text = "Samsung Galaxy A05s",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppPalette.TextSub
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1E2923)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(AppPalette.MintHero)
                                )
                            }
                            Text(
                                text = "Personal Backup",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppPalette.TextMain
                            )
                        }
                    }

                    // Setup button styled as a clean modern pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(AppPalette.CardBackground)
                            .border(1.dp, AppPalette.CardBorder, RoundedCornerShape(20.dp))
                            .clickable { showSettingsDialog = true }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Setup",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppPalette.TextMain
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 0. Over-The-Air Update Banner (Appears when GitHub has a newer version)
                if (updateInfo != null && updateInfo!!.hasUpdate) {
                    val info = updateInfo!!
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(AppPalette.MintSurface)
                            .border(1.dp, AppPalette.MintBorder, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(AppPalette.MintHero)
                                    )
                                    Text(
                                        text = "Update Available: v${info.latestVersion}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = AppPalette.MintText
                                    )
                                }
                                Text(
                                    text = "Current: v${info.currentVersion}",
                                    fontSize = 11.sp,
                                    color = AppPalette.TextSub
                                )
                            }
                            val displayNotes = info.releaseNotes
                                .lines()
                                .map { it.trim().replace(Regex("^#+\\s*"), "").replace(Regex("^[-*]\\s*"), "• ") }
                                .filter { it.isNotBlank() }
                                .joinToString("\n")
                                .take(250)
                            Text(
                                text = displayNotes,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = AppPalette.TextSub
                            )
                            if (isDownloadingUpdate) {
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { updateDownloadProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = AppPalette.MintHero,
                                    trackColor = AppPalette.CardBorder
                                )
                                Text(
                                    text = "Downloading update: ${(updateDownloadProgress * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    color = AppPalette.TextSub
                                )
                            } else {
                                Button(
                                    onClick = {
                                        val downloadUrl = info.apkDownloadUrl ?: return@Button
                                        isDownloadingUpdate = true
                                        CoroutineScope(Dispatchers.IO).launch {
                                            val result = appUpdater.downloadAndInstallApk(downloadUrl) { progress ->
                                                CoroutineScope(Dispatchers.Main).launch {
                                                    updateDownloadProgress = progress
                                                }
                                            }
                                            withContext(Dispatchers.Main) {
                                                isDownloadingUpdate = false
                                                result.onSuccess { apkFile ->
                                                    Toast.makeText(this@MainActivity, "Update downloaded! Preparing install...", Toast.LENGTH_SHORT).show()
                                                    appUpdater.promptInstall(apkFile)
                                                }.onFailure { err ->
                                                    Toast.makeText(this@MainActivity, "Update download failed: ${err.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppPalette.MintHero),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().height(42.dp)
                                ) {
                                    Text("Download & Install v${info.latestVersion}", fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                }

                // 0.5 Permission Setup Banner (Displays on first install or whenever essential permissions are ungranted)
                if (missingPermissionsCount > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(AppPalette.BlushSurface)
                            .border(1.dp, AppPalette.BlushBorder, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(AppPalette.BlushHero)
                                    )
                                    Text(
                                        text = "Initial Setup: Permissions Required",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = AppPalette.BlushText
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(AppPalette.CardBackground)
                                        .border(1.dp, AppPalette.BlushBorder, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "$missingPermissionsCount needed",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppPalette.BlushText
                                    )
                                }
                            }
                            Text(
                                text = "Personal Backup needs Photos, Videos & Notification access to continuously safeguard your memories to your Windows PC without data loss.",
                                fontSize = 12.sp,
                                color = AppPalette.TextSub,
                                lineHeight = 16.sp
                            )
                            Button(
                                onClick = {
                                    requestRequiredPermissions()
                                    missingPermissionsCount = getMissingPermissions().size
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppPalette.BlushHero),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(42.dp)
                            ) {
                                Text("Grant All Required Permissions", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }

                // 1. Connection Status Banner (Cloud Storage: CONNECTED / OFFLINE)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (serverConnected) AppPalette.MintSurface else AppPalette.BlushSurface)
                        .border(
                            1.dp,
                            if (serverConnected) AppPalette.MintBorder else AppPalette.BlushBorder,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { showSettingsDialog = true }
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left Column (Given weight so it never pushes or squashes the right button)
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (serverConnected) AppPalette.MintBorder else AppPalette.BlushBorder),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(if (serverConnected) AppPalette.MintHero else AppPalette.BlushHero)
                                )
                            }
                            Column {
                                Text(
                                    text = if (serverConnected) "Cloud Storage: CONNECTED" else "Cloud Storage: OFFLINE",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (serverConnected) AppPalette.MintText else AppPalette.BlushText
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                // IP address hidden from main screen for clean privacy
                                Text(
                                    text = if (serverConnected) "Private Archive • $serverFreeSpace" else "Private Storage • Tap to connect",
                                    fontSize = 12.sp,
                                    color = AppPalette.TextSub
                                )
                            }
                        }

                        // Right Button ("Configure ›" in a dedicated non-squeezing pill badge)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppPalette.CardBackground)
                                .border(
                                    1.dp,
                                    if (serverConnected) AppPalette.MintBorder else AppPalette.BlushBorder,
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Configure ›",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                color = if (serverConnected) AppPalette.MintText else AppPalette.BlushText
                            )
                        }
                    }
                }

                // 2. Dual Metric Cards (Blush Pink for Pending, Mint Green for Verified)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Pending Queue Card (Delicate Light Blush Pink)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(AppPalette.BlushCardGradient)
                            .border(1.dp, AppPalette.BlushBorder, RoundedCornerShape(18.dp))
                            .padding(16.dp)
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AppPalette.BlushSurface)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "Pending Queue",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppPalette.BlushText
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "$queueCount",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = AppPalette.TextMain
                            )
                            Text(
                                text = "Items to archive",
                                fontSize = 11.sp,
                                color = AppPalette.TextSub
                            )
                        }
                    }

                    // Archived & Verified Card (Delicate Light Mint Green)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(AppPalette.MintCardGradient)
                            .border(1.dp, AppPalette.MintBorder, RoundedCornerShape(18.dp))
                            .padding(16.dp)
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AppPalette.MintSurface)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "Archived & Verified",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppPalette.MintText
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "$verifiedCount",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = AppPalette.MintHero
                            )
                            Text(
                                text = "Safe on laptop",
                                fontSize = 11.sp,
                                color = AppPalette.TextSub
                            )
                        }
                    }
                }

                // 3. Current Activity & Progress Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(AppPalette.CardBackground)
                        .border(1.dp, AppPalette.CardBorder, RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Live Activity",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = AppPalette.TextMain
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AppPalette.MintSurface)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${(currentProgress * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppPalette.MintText
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = currentStatusText,
                            fontSize = 12.sp,
                            color = AppPalette.TextSub,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { currentProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = AppPalette.MintHero,
                            trackColor = AppPalette.MintSurface
                        )
                    }
                }

                // 4. Primary Hero Action: BACK UP EVERYTHING (Lighter Soft Mint Gradient)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (!isOperating) AppPalette.MintHeroGradient else Brush.horizontalGradient(listOf(Color(0xFFBBF7D0), Color(0xFFBBF7D0))))
                        .clickable(enabled = !isOperating) {
                            isOperating = true
                            currentStatusText = "Scanning accessible gallery & media library..."
                            CoroutineScope(Dispatchers.Default).launch {
                                val newFound = engine.scanMediaStore()
                                withContext(Dispatchers.Main) {
                                    currentStatusText = "Uploading $newFound items to Windows Archive..."
                                }
                                engine.processQueue { item, transferred, total ->
                                    val pct = if (total > 0) transferred.toFloat() / total.toFloat() else 0f
                                    CoroutineScope(Dispatchers.Main).launch {
                                        currentProgress = pct
                                        currentStatusText = "Transferring: ${item.displayName}"
                                    }
                                }
                                engine.reconcileWithServer()
                                withContext(Dispatchers.Main) {
                                    isOperating = false
                                    currentProgress = 1.0f
                                    currentStatusText = "Complete. Verified with Windows archive."
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isOperating) "Operating in background..." else "Back Up Everything",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // 5. Action Tile: Restore Archive to Device (Light Blush Pink Surface)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(AppPalette.BlushSurface)
                        .border(1.dp, AppPalette.BlushBorder, RoundedCornerShape(16.dp))
                        .clickable(enabled = !isOperating) {
                            isOperating = true
                            currentStatusText = "Querying Windows archive for files to restore..."
                            CoroutineScope(Dispatchers.Default).launch {
                                val (restored, skipped) = restoreEngine.restoreAllFromArchive { current, total, name ->
                                    val pct = if (total > 0) current.toFloat() / total.toFloat() else 0f
                                    CoroutineScope(Dispatchers.Main).launch {
                                        currentProgress = pct
                                        currentStatusText = "Restoring ($current/$total): $name"
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    isOperating = false
                                    currentProgress = 1.0f
                                    currentStatusText = "Restoration complete! Restored $restored files ($skipped already present)."
                                    Toast.makeText(this@MainActivity, "Restored $restored items to Gallery!", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Restore Archive to This Device",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = AppPalette.BlushText
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Download verified photos back to gallery with re-upload immunity",
                                fontSize = 11.sp,
                                color = AppPalette.TextSub
                            )
                        }
                        Text("›", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppPalette.BlushText)
                    }
                }

                // 6. Secondary Action Tiles (Manual File Backup & Deleted-Media Recovery)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Manual File Backup Tile
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(AppPalette.CardBackground)
                            .border(1.dp, AppPalette.CardBorder, RoundedCornerShape(16.dp))
                            .clickable(enabled = !isOperating) {
                                documentPickerLauncher.launch(arrayOf("*/*"))
                            }
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(
                                text = "Manual Files",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = AppPalette.TextMain
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "PDF, Docs, ZIPs",
                                fontSize = 11.sp,
                                color = AppPalette.TextSub
                            )
                        }
                    }

                    // Deleted-Media Recovery Tool Tile
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(AppPalette.CardBackground)
                            .border(1.dp, AppPalette.CardBorder, RoundedCornerShape(16.dp))
                            .clickable(enabled = !isOperating) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
                                    requestAllFilesAccess()
                                }
                                showRecoveryDialog = true
                            }
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(
                                text = "Media Recovery",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = AppPalette.TextMain
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Trash & Remnants",
                                fontSize = 11.sp,
                                color = AppPalette.TextSub
                            )
                        }
                    }
                }

                // 7. Replacement Phone Setup Footer Link
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppPalette.CardBackground)
                        .border(1.dp, AppPalette.CardBorder, RoundedCornerShape(14.dp))
                        .clickable { showNewPhoneDialog = true }
                        .padding(vertical = 12.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Replacement / New Phone Recovery Setup ›",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppPalette.MintText
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Settings / Pairing Dialog (Mint & White themed)
        if (showSettingsDialog) {
            var hostInput by remember { mutableStateOf(prefs.serverHost) }
            var portInput by remember { mutableStateOf(prefs.serverPort.toString()) }
            var codeInput by remember { mutableStateOf("BACKUP-7749") }
            var pairingStatus by remember { mutableStateOf("") }
            var generatedRecoveryKey by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                containerColor = AppPalette.CardBackground,
                title = {
                    Text("Initial Device Pairing", fontWeight = FontWeight.Bold, color = AppPalette.TextMain)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Enter your Cloud Storage server IP (Wi-Fi or Tailscale) and pairing code.",
                            fontSize = 12.sp,
                            color = AppPalette.TextSub
                        )
                        OutlinedTextField(
                            value = hostInput,
                            onValueChange = { hostInput = it },
                            label = { Text("Server Host IP") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { portInput = it },
                            label = { Text("Port (default 8976)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = { codeInput = it },
                            label = { Text("Pairing Code") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        if (pairingStatus.isNotEmpty()) {
                            Text(
                                pairingStatus,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (pairingStatus.contains("success", ignoreCase = true)) AppPalette.MintHero else AppPalette.BlushHero
                            )
                        }
                        if (generatedRecoveryKey.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AppPalette.MintSurface)
                                    .border(1.dp, AppPalette.MintBorder, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("IMPORTANT: Save Your Recovery Key", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AppPalette.MintText)
                                    Text(generatedRecoveryKey, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = AppPalette.TextMain)
                                    Text("Keep this safe. If this phone is lost, you will need this key to restore your archive onto a replacement phone.", fontSize = 10.sp, color = AppPalette.TextSub)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val host = hostInput.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
                            val port = portInput.trim().toIntOrNull() ?: 8976
                            val code = codeInput.trim()

                            if (host.isBlank()) {
                                pairingStatus = "Please enter the server IP"
                                return@Button
                            }
                            if (code.isBlank()) {
                                pairingStatus = "Please enter the pairing code"
                                return@Button
                            }

                            prefs.serverHost = host
                            prefs.serverPort = port
                            pairingStatus = "Pairing with $host:$port..."

                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val (token, rcvKey) = apiClient.pair(
                                        host,
                                        port,
                                        code,
                                        prefs.deviceId,
                                        prefs.deviceName
                                    )

                                    withContext(Dispatchers.Main) {
                                        if (token != null) {
                                            prefs.authToken = token
                                            if (rcvKey != null) generatedRecoveryKey = rcvKey
                                            pairingStatus = "Paired successfully!"
                                            Toast.makeText(this@MainActivity, "Device paired!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            pairingStatus = "Pairing failed. Check IP ($host:$port) & code."
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        pairingStatus = "Connection error: ${e.localizedMessage ?: e.message}"
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppPalette.MintHero),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Pair Now", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSettingsDialog = false }) {
                        Text("Close", color = AppPalette.TextSub)
                    }
                }
            )
        }

        // Replacement Phone Recovery Dialog
        if (showNewPhoneDialog) {
            var hostInput by remember { mutableStateOf(prefs.serverHost) }
            var portInput by remember { mutableStateOf(prefs.serverPort.toString()) }
            var recoveryKeyInput by remember { mutableStateOf("") }
            var recoveryStatus by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { showNewPhoneDialog = false },
                containerColor = AppPalette.CardBackground,
                title = { Text("Restore to New/Reset Phone", fontWeight = FontWeight.Bold, color = AppPalette.TextMain) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Enter the Recovery Key provided during your initial setup to link this device to your existing Cloud Storage archive.", fontSize = 12.sp, color = AppPalette.TextSub)
                        OutlinedTextField(
                            value = hostInput,
                            onValueChange = { hostInput = it },
                            label = { Text("Server IP") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { portInput = it },
                            label = { Text("Port (8976)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = recoveryKeyInput,
                            onValueChange = { recoveryKeyInput = it },
                            label = { Text("Recovery Key (RCV-XXXX-...)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        if (recoveryStatus.isNotEmpty()) {
                            Text(
                                recoveryStatus,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (recoveryStatus.contains("success", ignoreCase = true)) AppPalette.MintHero else AppPalette.BlushHero
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val host = hostInput.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
                            val port = portInput.trim().toIntOrNull() ?: 8976
                            val key = recoveryKeyInput.trim()

                            if (host.isBlank() || key.isBlank()) {
                                recoveryStatus = "Please enter server IP and recovery key"
                                return@Button
                            }

                            prefs.serverHost = host
                            prefs.serverPort = port
                            recoveryStatus = "Authorizing replacement device..."

                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val token = apiClient.recover(
                                        host,
                                        port,
                                        key,
                                        prefs.deviceId,
                                        "Replacement Samsung Galaxy A05s"
                                    )

                                    withContext(Dispatchers.Main) {
                                        if (token != null) {
                                            prefs.authToken = token
                                            recoveryStatus = "Authorized successfully! You can now tap 'Restore Archive to This Device'."
                                            Toast.makeText(this@MainActivity, "Replacement Phone Authorized!", Toast.LENGTH_LONG).show()
                                        } else {
                                            recoveryStatus = "Authorization failed. Check IP & Recovery Key."
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        recoveryStatus = "Error: ${e.localizedMessage ?: e.message}"
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppPalette.MintHero),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Authorize Device", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNewPhoneDialog = false }) {
                        Text("Close", color = AppPalette.TextSub)
                    }
                }
            )
        }

        // Deleted-Media Recovery Dialog (Forensic Multi-Tiered Subsystem)
        if (showRecoveryDialog) {
            var report by remember { mutableStateOf<com.backup.personal.recovery.RecoveryDiagnosticReport?>(null) }
            var isLoadingReport by remember { mutableStateOf(true) }
            var scanProgressText by remember { mutableStateOf("Auditing storage & recovery sources...") }
            var recoveryStatus by remember { mutableStateOf("") }
            var isOperatingRecovery by remember { mutableStateOf(false) }
            var queueProgressText by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    val rep = recoveryScanner.getDiagnosticReport { progress ->
                        scanProgressText = progress
                    }
                    withContext(Dispatchers.Main) {
                        report = rep
                        isLoadingReport = false
                    }
                }
            }

            AlertDialog(
                onDismissRequest = { if (!isOperatingRecovery) showRecoveryDialog = false },
                containerColor = AppPalette.CardBackground,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(AppPalette.MintHero)
                        )
                        Text(
                            text = "Forensic Media Recovery",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = AppPalette.TextMain
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isLoadingReport || report == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = AppPalette.MintHero,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Text(
                                        text = scanProgressText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = AppPalette.TextSub,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }
                        } else {
                            val r = report!!
                            // Device & OS Metadata
                            Text(
                                text = "Device: ${r.deviceModel} • Android API ${r.androidVersion}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = AppPalette.TextMain
                            )
                            Text(
                                text = "Sandbox Security: ${if (r.isRooted) "Root UID 0" else "Unrooted Standard Sandbox (SELinux Enforcing)"}",
                                fontSize = 11.sp,
                                color = if (r.isRooted) AppPalette.MintText else AppPalette.TextSub
                            )

                            HorizontalDivider(color = AppPalette.CardBorder)

                            // Recoverable Sources Breakdown
                            Text(
                                text = "Recoverable Media Sources:",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = AppPalette.TextMain
                            )

                            // Tier 1: MediaStore Trash
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("• System MediaStore Trash", fontSize = 12.sp, color = AppPalette.TextSub)
                                Text("${r.trashedItemsFound} items", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (r.trashedItemsFound > 0) AppPalette.MintHero else AppPalette.TextMuted)
                            }

                            // Tier 2: Dynamic Vendor Gallery Trash (Xiaomi / Samsung / ColorOS)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("• ${r.vendorTrashLabel}", fontSize = 12.sp, color = AppPalette.TextSub)
                                Text("${r.vendorTrashItemsFound} items", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (r.vendorTrashItemsFound > 0) AppPalette.MintHero else AppPalette.TextMuted)
                            }

                            // Tier 3: Social App Caches
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("• WhatsApp & App Media Copies", fontSize = 12.sp, color = AppPalette.TextSub)
                                Text("${r.appCacheCopiesFound} items", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (r.appCacheCopiesFound > 0) AppPalette.MintHero else AppPalette.TextMuted)
                            }

                            // Tier 4: Thumbnail & Cache Remnants
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("• Thumbnails & Cache Remnants", fontSize = 12.sp, color = AppPalette.TextSub)
                                Text("${r.thumbnailRemnantsFound} previews", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (r.thumbnailRemnantsFound > 0) AppPalette.MintHero else AppPalette.TextMuted)
                            }

                            // Tier 5: Removable SD Card
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("• Removable SD (LOST.DIR / FAT)", fontSize = 12.sp, color = AppPalette.TextSub)
                                Text(
                                    if (r.removableStorageFound) "${r.removableStorageRemnantsFound} items" else "No SD Card",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (r.removableStorageRemnantsFound > 0) AppPalette.MintHero else AppPalette.TextMuted
                                )
                            }

                            // Deep Storage Status
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { if (!hasAllFilesAccess()) requestAllFilesAccess() },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("• All Files Access (Deep Scan)", fontSize = 12.sp, color = if (hasAllFilesAccess()) AppPalette.TextSub else AppPalette.BlushText)
                                Text(
                                    text = if (hasAllFilesAccess()) "Active" else "Tap to Grant ›",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasAllFilesAccess()) AppPalette.MintHero else AppPalette.BlushText
                                )
                            }

                            HorizontalDivider(color = AppPalette.CardBorder)

                            // Forensic Honesty Notice
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AppPalette.CanvasBackground)
                                    .border(1.dp, AppPalette.CardBorder, RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                                ) {
                                Text(
                                    text = r.limitationsExplanation,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                    color = AppPalette.TextSub
                                )
                            }

                            if (recoveryStatus.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = recoveryStatus,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppPalette.MintHero
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !isLoadingReport && !isOperatingRecovery && (report?.totalRecoverableCount ?: 0) > 0,
                        onClick = {
                            isOperatingRecovery = true
                            queueProgressText = ""
                            recoveryStatus = "Initializing recovery staging..."
                            CoroutineScope(Dispatchers.IO).launch {
                                val queued = recoveryScanner.scanAndQueueRecoverableMedia(
                                    onScanProgress = { text ->
                                        CoroutineScope(Dispatchers.Main).launch { recoveryStatus = text }
                                    },
                                    onQueueProgress = { current, total ->
                                        CoroutineScope(Dispatchers.Main).launch {
                                            queueProgressText = "$current / $total"
                                            recoveryStatus = "Archiving recovered items ($current / $total)..."
                                        }
                                    }
                                )
                                withContext(Dispatchers.Main) {
                                    isOperatingRecovery = false
                                    recoveryStatus = "Successfully queued $queued items for Windows archival!"
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Queued $queued recovered items into archive pipeline!",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    BackupService.startBackup(this@MainActivity)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppPalette.MintHero),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (isOperatingRecovery) {
                                if (queueProgressText.isNotEmpty()) "Archiving ($queueProgressText)" else "Archiving..."
                            } else {
                                "Queue & Archive (${report?.totalRecoverableCount ?: 0})"
                            },
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !isOperatingRecovery,
                        onClick = { showRecoveryDialog = false }
                    ) {
                        Text("Close", color = AppPalette.TextSub)
                    }
                }
            )
        }
    }
}

