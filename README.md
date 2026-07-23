# 扫库备份助手 (nomedia-scan-backup)

一个安卓小工具：把带 `.nomedia`、平时不被系统扫描进相册的**超大图片文件夹**临时“解除隐藏”，触发系统媒体扫描，让图片进入系统相册（MediaStore），从而让网盘用**稳定的【相册/图片自动备份】**通道来备份；备份完成后一键恢复 `.nomedia`，把图片重新隐去。

适用场景：手机里有一个 100G+、成千上万张图片的文件夹（含 `.nomedia`），直接用网盘“文件夹备份”不稳定/易断，改走相册备份更可靠。

## 为什么这样做

- 删掉 `.nomedia` **本身不会**让系统立刻重扫，必须主动触发一次扫描。
- `.nomedia` 对子目录**递归生效**，所以只需管理文件夹根部这一个文件。
- 图片进入系统相册后，网盘的“相册自动备份”是增量、可续传的，比整目录备份稳。

## 工作流程

**进入备份模式**：把根部 `.nomedia` 重命名为 `.nomedia.bak` → 触发系统扫描 → 图片进入相册 → 去网盘开【相册自动备份】。

**退出备份模式**：把 `.nomedia.bak` 改回 `.nomedia`（或新建）→ 再扫一次 → 图片从相册移出。

## 权限设计（尽量低）

| 能力 | 依赖 |
|---|---|
| 读写 `.nomedia` | 一次性 SAF 文件夹授权（`OPEN_DOCUMENT_TREE`），**无需**“所有文件访问” |
| 触发系统扫描 | `MediaScannerConnection`，无需任何权限 |
| 显示“已入库 N 张”进度 | 可选 `READ_MEDIA_IMAGES/VIDEO`，不给也能用 |

不需要 Shizuku、不需要 Root、不需要 `MANAGE_EXTERNAL_STORAGE`。

## 技术栈

Kotlin + Jetpack Compose (Material3)，minSdk 26 / targetSdk 34，Gradle 8.14 + AGP 8.2.2。目标机型：HyperOS 3（及其它使用主线 MediaProvider 的现代安卓）。

## 构建

CI（GitHub Actions）会在 push 到 `main` 时自动构建 debug APK（Artifact），打 tag（如 `v0.0.1-beta`）时创建 Release 并附上可直接侧载的 debug APK。

本地构建：

```bash
cd android
./gradlew assembleDebug
# 产物：android/app/build/outputs/apk/debug/nomedia-backup-*-debug.apk
```

## 关键代码

- `NomediaManager.kt` — SAF 下对 `.nomedia` 的 重命名/新建/删除，以及从 tree URI 推导真实路径。
- `SystemScanner.kt` — 目录级系统扫描（主）+ 逐文件深度扫描（兜底）+ MediaStore 计数进度。
- `MainActivity.kt` — Compose 单屏 UI 与状态机。

## 提示

- 首次扫描 150G 大文件夹可能需要几十分钟，属正常，之后增量很快。
- 备份期间图片会出现在系统相册、对所有 App 可见，退出备份模式后隐回。
- 若子目录里也各自有 `.nomedia`，那些子目录仍会被系统跳过；本工具默认只管根部那一个。
