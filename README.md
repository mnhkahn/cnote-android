# CNote Android

轻量、本地优先的 Android 笔记，包名 `com.cyeam.cnote`，Android 8.0（API 26）及以上。只包含笔记，没有 JSON、Diff、CSV 等工具。

原生 Kotlin 列表、搜索、菜单和系统文件选择器；富文本编辑器与 Bootstrap 图标随 APK 打包，通过本地 HTTPS origin 加载，不加载 cyeam.com 页面或远程脚本。支持标题、列表、待办、表格、图片、撤销/重做、Markdown 输入快捷方式、HTML 导出和系统打印。

## 数据与同步

- 无需登录即可使用。SQLite WAL + FULL 持久化；输入停止约 200 ms 后保存，连续输入约每秒生成快照。磁盘写入与 JSON 解析在后台执行。返回列表、退出、登录切换时刷新待保存内容；明确退出、删除及切换账号会等待本地保存确认。
- 一个编辑器实例，通过可切换 Context 的包装器随 ViewModel 保留，Activity 配置重建时也保留未落盘的输入，避免持有旧 Activity。最近笔记保留实际编辑树；已缓存笔记切换不重新加载网页、不重新解析 HTML。列表不查询正文或图片。缓存仅淘汰已保存且非当前的笔记，非当前已保存缓存最多 8 篇 / 约 16 MiB；待保存草稿不会淘汰。
- Microsoft 个人账号设备码登录，`Files.ReadWrite User.Read offline_access`，与桌面端同一个公开客户端 ID。Session 使用 Android Keystore AES-GCM 加密，凭据密钥不写入项目。关闭 Android 备份，退出登录清除凭据，账号所属笔记保留并按账号隔离。
- OneDrive `Notes/*.html` 与桌面版/网页共用；使用 ETag 条件写入，保留同步期间的新编辑。版本冲突、远端删除、较大内容替换均需处理后才继续上传。列表底部状态或编辑菜单“处理同步问题”可操作。
- 前台保存后约 2 秒尝试上传。JobScheduler 在网络可用时重试，遵循 Android 电量/后台调度限制。手动“立即同步”刷新云端列表；打开笔记时下载最新版。未下载的云端笔记在离线时保持只读。
- 正文最大 20 MiB，单张插入图片最大 8 MiB。尚未生成快照的短暂输入在进程被系统强杀时仍可能丢失；“已保存到本机”表示 SQLite 已确认写入。

## Google / Firebase 统计

`app/google-services.json` 已从提供的配置中只保留 `com.cyeam.cnote` 客户端。使用 Firebase Analytics 原生 SDK。release 默认开启，debug 默认关闭，用户可在列表右上角菜单切换。

事件：`note_created`、`note_deleted`、`login_completed`、`sync_completed`、`screen_view`；默认参数 `app=cnote_android`、`platform=android`、`app_version`。不上传笔记正文、标题、账号、令牌。关闭广告 ID 与广告个性化信号。Firebase 控制台按 Android 应用流区分，探索中还可按 `app` 参数区分（需在控制台注册自定义维度）。

## 构建

JDK 21、Android SDK 35；Gradle Wrapper 8.11.1 / AGP 8.10.1 / Kotlin 2.0.21。首次构建需要网络下载依赖。

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。本地 SDK 路径放在忽略的 `local.properties`。

签名 release（密钥只生成一次）：

```sh
python3 scripts/create-signing-key.py
./gradlew testDebugUnitTest lintRelease assembleRelease -PreleaseVersion=0.1.1
```

`.signing/` 已忽略，必须私下备份密钥与密码；以后覆盖安装需要同一签名。release APK 在 `app/build/outputs/apk/release/`，R8 mapping 同时保留。debug 与 release 签名不同，互相覆盖安装会失败，请避免通过卸载丢失未同步数据。

## 自动升级与蒲公英发布

应用启动后在后台检查 `https://www.cyeam.com/api/apps/cnote/update`，复用 homework 的蒲公英更新网关协议；API Key 仅放在服务端和 Actions Secrets。网关未注册、不可用或网络失败时，读取本仓库 GitHub Release 的 `update.json` 镜像。两个渠道都校验 appId、包名和 APK versionCode；网关下载令牌必须未过期、地址必须为本网关的 CNote 下载路由；镜像下载地址固定在本仓库 Release 中，另带 SHA-256。

发现新版后，通过系统 DownloadManager 在非计费网络后台下载，记录持久化，进程重启后继续监控同一个任务。下载完成后校验大小、包名、目标 versionCode 与当前应用签名；镜像下载还校验 SHA-256。只给 FileProvider 暴露专用更新目录。安装前再次校验，并等待所有笔记写入本机后才打开系统安装器；首次需要允许 CNote 安装未知应用，安装最终由 Android 系统确认。

列表和编辑页右上角菜单都提供“检查更新”；可以查看进度、重试失败下载及安装已下载的版本。自动检查仅在 release 默认启用，debug 可手动检查，但会拒绝签名不同的 release 安装包。后台检查每进程最多每六小时一次，手动检查可立即重试；不会阻塞列表、编辑和笔记同步。

- `android.yml`：main / PR 构建、单元测试、Python 发布脚本测试、Lint、上传 debug APK；独立 API 35 模拟器执行真实 SQLite / Keystore / WebView / APK 校验测试。
- `release.yml`：推送 `vX.Y.Z` / `X.Y.Z` tag，或手动输入稳定版本号后，检查版本递增、构建并签名 APK、生成更新说明、发布蒲公英并等待真正可下载，再创建 GitHub Release（APK、更新清单、R8 mapping）。Actions Summary 中显示两个下载入口。
- versionCode 保留现有规则 `major*1000000 + minor*1000 + patch`，例如 0.1.0 为 1000，0.1.1 为 1001；不能照搬其他项目的小 workflow run number，否则无法覆盖已安装的 CNote。minor/patch 最大 999。

仓库 Actions Secrets：`CNOTE_KEYSTORE_BASE64`、`CNOTE_STORE_PASSWORD`、`PGYER_API_KEY`。本次已配置，CNote 始终使用自己的 `.signing/release.jks`，不使用 homework 的签名。

蒲公英发布脚本：`scripts/publish_pgyer.py`（获取凭证 → 上传 → 轮询 buildInfo；发布失败或 APK 元信息不符会让流水线失败）。`scripts/release.py` 从实际 APK 读取包名、版本并生成更新清单，API Key 不会进入 APK、清单或公开日志。

首次启用需要将本项目代码提交并推送到 GitHub。蒲公英首版与 `cyeam_web/conf/app_updates.json` 的 CNote 短链接配置信息写入本次验证记录；该后端配置需按网站已有发布流程部署后才启用主网关，GitHub 镜像独立可用。

## 验证

JUnit 覆盖安全文件名、大改动判定、上传确认与编辑/删除并发。Android 仪器测试覆盖离线保存及重开、账号隔离、超过 CursorWindow 大小的内嵌图片、占位笔记保护、冲突选择、精确版本确认、Session 加密与防篡改，以及实际本地 WebView 的保存、编辑树复用、重建恢复与 HTML 清理。

Bootstrap Icons 授权见 `app/src/main/assets/editor/Bootstrap-Icons-LICENSE.txt`。笔记图标复用自现有 CNote 项目。

界面截图：[编辑器](docs/screenshots/editor.png)。
