package com.nomedia.backup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.nomedia.backup.ui.theme.NomediaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NomediaTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen()
                }
            }
        }
    }
}

private const val PREFS = "nomedia_prefs"
private const val KEY_TREE = "tree_uri"

private fun loadSavedTree(context: Context): Uri? {
    val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null) ?: return null
    val uri = Uri.parse(s)
    val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    return if (held) uri else null
}

private fun saveTree(context: Context, uri: Uri) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply()
}

private fun hasMediaReadPermission(context: Context): Boolean {
    val perms = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    return perms.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { NomediaManager(context) }
    val scanner = remember { SystemScanner(context) }

    var treeUri by remember { mutableStateOf(loadSavedTree(context)) }
    var status by remember { mutableStateOf(NomediaManager.Status.UNKNOWN) }
    var folderName by remember { mutableStateOf("") }
    var folderPath by remember { mutableStateOf<String?>(null) }

    var busy by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var indexedCount by remember { mutableStateOf(-1) }
    var hasMediaPerm by remember { mutableStateOf(hasMediaReadPermission(context)) }

    fun updateCount() {
        val path = folderPath
        if (path == null || !hasMediaPerm) { indexedCount = -1; return }
        scope.launch { indexedCount = withContext(Dispatchers.IO) { scanner.countIndexed(path) } }
    }

    fun refresh() {
        treeUri?.let { uri ->
            scope.launch {
                val s = withContext(Dispatchers.IO) { manager.status(uri) }
                val name = manager.displayName(uri)
                val path = manager.realPath(uri)
                status = s; folderName = name; folderPath = path
                updateCount()
            }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try { context.contentResolver.takePersistableUriPermission(uri, flags) } catch (_: Exception) {}
            saveTree(context, uri)
            treeUri = uri
            message = ""
            refresh()
        }
    }

    val requestPerms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasMediaPerm = hasMediaReadPermission(context)
        refresh()
    }

    LaunchedEffect(treeUri) { refresh() }

    fun pollCountWhile(active: () -> Boolean): Job = scope.launch {
        val path = folderPath ?: return@launch
        if (!hasMediaPerm) return@launch
        while (isActive && active()) {
            indexedCount = withContext(Dispatchers.IO) { scanner.countIndexed(path) }
            delay(2000)
        }
        indexedCount = withContext(Dispatchers.IO) { scanner.countIndexed(path) }
    }

    fun enterBackupMode() {
        val uri = treeUri ?: return
        val path = folderPath
        if (path == null) { message = "无法解析文件夹的真实路径，可能不在内部存储。"; return }
        busy = true; message = ""
        val poller = pollCountWhile { busy }
        scope.launch {
            phase = "正在移走 .nomedia …"
            val ok = withContext(Dispatchers.IO) { manager.moveOut(uri) }
            if (!ok) { busy = false; poller.cancel(); message = "移走 .nomedia 失败，请检查文件夹授权。"; refresh(); return@launch }
            status = manager.status(uri)
            phase = "系统正在扫描文件夹（大文件夹可能需要几十分钟，可切后台等待）…"
            withContext(Dispatchers.IO) { scanner.scanDirectory(path) }
            busy = false; poller.cancel()
            refresh()
            message = "已进入备份模式：图片正在进入系统相册。现在去网盘 App 打开【相册 / 图片自动备份】即可稳定上传。"
        }
    }

    fun exitBackupMode() {
        val uri = treeUri ?: return
        val path = folderPath
        if (path == null) { message = "无法解析文件夹的真实路径。"; return }
        busy = true; message = ""
        val poller = pollCountWhile { busy }
        scope.launch {
            phase = "正在恢复 .nomedia …"
            val ok = withContext(Dispatchers.IO) { manager.moveIn(uri) }
            if (!ok) { busy = false; poller.cancel(); message = "恢复 .nomedia 失败。"; refresh(); return@launch }
            status = manager.status(uri)
            phase = "系统正在重扫以将图片移出相册 …"
            withContext(Dispatchers.IO) { scanner.scanDirectory(path) }
            busy = false; poller.cancel()
            refresh()
            message = "已退出备份模式：.nomedia 已恢复，图片将从系统相册中隐去。"
        }
    }

    fun deepScan() {
        val uri = treeUri ?: return
        busy = true; message = ""
        val poller = pollCountWhile { busy }
        scope.launch {
            phase = "逐文件深度扫描中 …"
            val n = withContext(Dispatchers.IO) {
                scanner.deepScanFiles(manager, uri) { p ->
                    phase = "深度扫描：已提交 ${p.scanned} 个文件（目录 ${p.dirsVisited}）"
                }
            }
            busy = false; poller.cancel()
            refresh()
            message = "深度扫描完成：共向系统提交 $n 个媒体文件。"
        }
    }

    val scroll = rememberScrollState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("扫库备份助手") }) }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (treeUri == null) {
                IntroCard()
                Button(onClick = { pickFolder.launch(null) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("选择要备份的文件夹")
                }
            } else {
                FolderCard(folderName, folderPath, status, indexedCount, hasMediaPerm)

                if (!hasMediaPerm) {
                    OutlinedButton(onClick = {
                        val perms = if (Build.VERSION.SDK_INT >= 33)
                            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                        requestPerms.launch(perms)
                    }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Visibility, null); Spacer(Modifier.width(8.dp))
                        Text("授予相册读取权限（仅用于显示进度）")
                    }
                }

                ActionCard(
                    title = "① 进入备份模式",
                    subtitle = "移走 .nomedia → 触发扫描，图片进入系统相册",
                    icon = Icons.Filled.CloudUpload,
                    enabled = !busy,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    onClick = { enterBackupMode() }
                )
                ActionCard(
                    title = "② 退出备份模式",
                    subtitle = "恢复 .nomedia → 重扫，图片移出系统相册",
                    icon = Icons.Filled.VisibilityOff,
                    enabled = !busy,
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = { exitBackupMode() }
                )

                if (busy) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(phase, style = MaterialTheme.typography.bodyMedium)
                            if (indexedCount >= 0) {
                                Text("系统相册中该文件夹已入库：$indexedCount 张",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                if (message.isNotBlank()) {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Text(message, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Divider()
                Text("辅助工具", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { deepScan() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Layers, null); Spacer(Modifier.width(8.dp))
                    Text("逐文件深度扫描（兜底，扫不全时用）")
                }
                TextButton(onClick = { pickFolder.launch(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Folder, null); Spacer(Modifier.width(8.dp)); Text("重新选择文件夹")
                }

                TipCard()
            }
        }
    }
}

@Composable
private fun IntroCard() {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("这是什么", style = MaterialTheme.typography.titleMedium)
            Text(
                "把一个带 .nomedia、平时不进相册的大文件夹，临时“解除隐藏”并让系统重扫，" +
                        "使图片进入系统相册，方便网盘用【相册自动备份】稳定上传；备份完再一键恢复隐藏。",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("全程只需一次文件夹授权，不需要“所有文件访问”权限。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FolderCard(
    name: String, path: String?, status: NomediaManager.Status, indexed: Int, hasPerm: Boolean
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Folder, null)
                Spacer(Modifier.width(8.dp))
                Text(name.ifBlank { "已选择文件夹" }, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (path != null) {
                Text(path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text("⚠️ 无法解析真实路径，扫描可能不可用（是否在 SD 卡或特殊目录？）",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(2.dp))
            val (label, color) = when (status) {
                NomediaManager.Status.HIDDEN -> "当前：已隐藏（含 .nomedia）" to MaterialTheme.colorScheme.tertiary
                NomediaManager.Status.VISIBLE -> "当前：可见（无 .nomedia）" to MaterialTheme.colorScheme.primary
                NomediaManager.Status.UNKNOWN -> "状态未知" to MaterialTheme.colorScheme.error
            }
            AssistChip(onClick = {}, label = { Text(label) }, leadingIcon = {
                Icon(if (status == NomediaManager.Status.HIDDEN) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
            })
            if (hasPerm && indexed >= 0) {
                Text("系统相册中该文件夹已入库：$indexed 张", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionCard(
    title: String, subtitle: String, icon: ImageVector,
    enabled: Boolean, container: androidx.compose.ui.graphics.Color, onClick: () -> Unit
) {
    Card(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TipCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("使用提示", style = MaterialTheme.typography.titleSmall)
            Text("1. 进入备份模式后，请在网盘 App 里用【相册/图片自动备份】，不要用“文件夹备份”。",
                style = MaterialTheme.typography.bodySmall)
            Text("2. 首次扫描 150G 大文件夹可能较久，进度数字会持续增长，扫完再开始备份更稳。",
                style = MaterialTheme.typography.bodySmall)
            Text("3. 备份期间这些图片会出现在系统相册、对所有 App 可见；备份完成后退出备份模式即可隐回。",
                style = MaterialTheme.typography.bodySmall)
            Text("4. 若系统扫描没扫全，用下方“逐文件深度扫描”兜底。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
