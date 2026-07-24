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
    var nomediaCount by remember { mutableStateOf(0) }
    var folderName by remember { mutableStateOf("") }
    var folderPath by remember { mutableStateOf<String?>(null) }

    var busy by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var indexedCount by remember { mutableStateOf(-1) }
    var hasMediaPerm by remember { mutableStateOf(hasMediaReadPermission(context)) }
    var scanState by remember { mutableStateOf("未触发") }

    fun updateCount() {
        val path = folderPath
        if (path == null || !hasMediaPerm) { indexedCount = -1; return }
        scope.launch { indexedCount = withContext(Dispatchers.IO) { scanner.countIndexed(path) } }
    }

    fun refresh() {
        treeUri?.let { uri ->
            scope.launch {
                val (s, cnt) = withContext(Dispatchers.IO) { manager.statusRoot(uri) }
                val name = manager.displayName(uri)
                val path = manager.realPath(uri)
                status = s; nomediaCount = cnt; folderName = name; folderPath = path
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

    /** After the scan is submitted, the OS indexes in the background — poll a bit longer so the count is closer to real. */
    suspend fun waitForIndexing(maxPolls: Int = 20) {
        repeat(maxPolls) {
            delay(2000)
            updateCount()
        }
    }

    fun deepScan() {
        val uri = treeUri ?: return
        busy = true; message = ""; scanState = "进行中（逐文件深度扫描）"
        val poller = pollCountWhile { busy }
        scope.launch {
            phase = "正在深度枚举所有文件（含带 .nomedia 的子目录）…"
            val n = withContext(Dispatchers.IO) {
                scanner.deepScanFiles(manager, uri, respectNomedia = false) { p ->
                    phase = "深度扫描：已提交 ${p.scanned} 个文件（目录 ${p.dirsVisited}）"
                }
            }
            phase = "文件已提交，等待系统完成入库…"
            waitForIndexing()
            busy = false; poller.cancel()
            scanState = if (indexedCount >= 0) "已完成（相册已入库 $indexedCount 张）" else "已完成（提交 $n 个文件）"
            message = "深度扫描完成：共向系统提交 $n 个媒体文件（含带 .nomedia 的子目录）。"
        }
    }

    // ---- .nomedia 状态开关：开=有 .nomedia（隐藏），关=无（可见） ----

    /** hidden=true 在根目录建立/恢复 .nomedia（隐藏）；false 移出根目录 .nomedia（可见）。只动根目录，不碰子文件夹。 */
    fun toggleNomedia(hidden: Boolean) {
        val uri = treeUri ?: return
        busy = true; message = ""; scanState = "未触发"
        scope.launch {
            phase = if (hidden) "正在恢复根目录的 .nomedia …" else "正在移出根目录的 .nomedia …"
            val ok = withContext(Dispatchers.IO) {
                if (hidden) manager.setHidden(uri) else manager.setVisible(uri)
            }
            val (s, cnt) = withContext(Dispatchers.IO) { manager.statusRoot(uri) }
            busy = false
            status = s; nomediaCount = cnt
            message = if (ok) {
                if (hidden) "已隐藏（根目录有 .nomedia）。点【触发系统扫描】让图片移出相册。"
                else "已可见（根目录无 .nomedia）。点【触发系统扫描】让图片进相册。"
            } else "操作失败，请检查文件夹授权。"
            updateCount()
        }
    }

    /** 触发系统扫描：递归枚举整棵子树并提交给 MediaScanner（不走目录级扫描，后者在 HyperOS 上不递归）。
     *  图片是否“进/出相册”取决于当前 .nomedia 状态。 */
    fun triggerScan() {
        val uri = treeUri ?: return
        busy = true; message = ""; scanState = "进行中（系统扫描）"
        val poller = pollCountWhile { busy }
        scope.launch {
            phase = "正在枚举并扫描媒体文件（大文件夹可能需数十分钟，可切后台等待）…"
            val n = withContext(Dispatchers.IO) {
                scanner.deepScanFiles(manager, uri, respectNomedia = true) { p ->
                    phase = "系统扫描：已提交 ${p.scanned} 个文件（已遍历 ${p.dirsVisited} 个目录）"
                }
            }
            phase = "文件已提交，等待系统完成入库…"
            waitForIndexing()
            busy = false; poller.cancel()
            scanState = if (indexedCount >= 0) "已完成（相册已入库 $indexedCount 张）" else "已完成（提交 $n 个文件）"
            message = "系统扫描完成：共向系统提交 $n 个媒体文件。"
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
                FolderCard(folderName, folderPath, status, nomediaCount, indexedCount, hasMediaPerm)

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

                // ---- .nomedia 状态开关：开=有 .nomedia（隐藏），关=无（可见） ----
                val hidden = status == NomediaManager.Status.HIDDEN
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(".nomedia 隐藏开关（仅根目录）", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (hidden) "开：根目录有 .nomedia，已隐藏（不进相册）"
                                else "关：根目录无 .nomedia，可见（进相册）",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(checked = hidden, enabled = !busy, onCheckedChange = { toggleNomedia(it) })
                    }
                }

                // ---- 系统扫描 ----
                Text("系统扫描", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { triggerScan() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(8.dp))
                    Text("触发系统扫描（递归扫描整棵可见子树，按开关状态刷新相册）")
                }
                OutlinedButton(onClick = { deepScan() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Layers, null); Spacer(Modifier.width(8.dp))
                    Text("逐文件深度扫描（连带 .nomedia 的子目录一起扫，补扫更全）")
                }

                if (busy) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(phase, style = MaterialTheme.typography.bodyMedium)
                            Text("扫描状态：$scanState", style = MaterialTheme.typography.bodySmall)
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

                if (!busy && scanState.isNotBlank()) {
                    Text("扫描状态：$scanState", style = MaterialTheme.typography.bodySmall)
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
    name: String, path: String?, status: NomediaManager.Status, nomediaCount: Int, indexed: Int, hasPerm: Boolean
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
                NomediaManager.Status.HIDDEN -> "当前：已隐藏（根目录有 .nomedia）" to MaterialTheme.colorScheme.tertiary
                NomediaManager.Status.VISIBLE -> "当前：可见（根目录无 .nomedia）" to MaterialTheme.colorScheme.primary
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

@Composable
private fun TipCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("使用提示", style = MaterialTheme.typography.titleSmall)
            Text("1. 上方是一个状态开关：开=根目录有 .nomedia（隐藏），关=无（可见）。拨动即切换，只影响所选文件夹的根目录，不改动任何子文件夹。",
                style = MaterialTheme.typography.bodySmall)
            Text("2. 开关只改变 .nomedia 状态，要让图片真正“进/出相册”，还需点【触发系统扫描】。",
                style = MaterialTheme.typography.bodySmall)
            Text("3. 想进相册：关掉开关 → 触发系统扫描。想移出相册：打开开关 → 触发系统扫描。",
                style = MaterialTheme.typography.bodySmall)
            Text("4. 扫描会递归整棵子树：每个子文件夹的图片都会一并被系统索引。",
                style = MaterialTheme.typography.bodySmall)
            Text("5. 首次扫描 150G 大文件夹可能较久，下方“扫描状态/已入库张数”会持续更新，扫完再备份更稳。",
                style = MaterialTheme.typography.bodySmall)
            Text("6. 若【触发系统扫描】后仍有零散图片没进相册，用【逐文件深度扫描】补扫（它会连同带 .nomedia 的子目录一起扫）。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
