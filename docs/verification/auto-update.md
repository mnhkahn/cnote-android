# 蒲公英发布与自动升级验证（0.1.1）

2026-10-22，参考 `~/code/homework` 的发布和更新机制。

## 已完成

- CNote 0.1.1 已通过蒲公英快速上传 API 发布，等待 `buildInfo` 返回成功，并校验发布信息的包名 `com.cyeam.cnote`、versionName `0.1.1`、versionCode `1001`。下载页：<https://www.pgyer.com/cnote-android>。
- `.github/workflows/release.yml` 仅在推送稳定版本 tag 时发布；普通 push 只构建测试。签名 APK、发布蒲公英、等待可下载、基于已有 tag 创建 GitHub Release 和 SHA-256 更新清单，并显示下载链接。
- 三个 GitHub Actions Secrets 已配置：`CNOTE_KEYSTORE_BASE64`、`CNOTE_STORE_PASSWORD`、`PGYER_API_KEY`。沿用 CNote 自己的签名，没有使用 homework 签名或在 APK 中嵌入蒲公英密钥。
- 自动检查主通道复用 Cyeam 多 App 蒲公英网关；网关未注册/不可用时可读取本仓库的 GitHub Release 镜像。公网镜像只接受本仓库的固定 APK 资产路径和有效 SHA-256。
- DownloadManager 仅在非计费网络下载；跨进程恢复同一任务，完成后校验 APK，前台提供安装入口。失败任务可重新获取更新链接重试；安装前等待编辑器全部草稿保存，并重新核验 APK。
- FileProvider 只允许专用更新目录；Android 安装来源授权与最后的系统安装确认由用户完成。

## 检查

- 11 个 JVM 测试通过：3 个原有 Rules 测试 + 8 个更新协议/URL/版本/过期令牌/摘要测试。
- 13 个 Android 仪器测试通过，完整输出见 `updater-device-tests.txt`。新增测试使用真实 APK 验证签名、包名、版本、文件大小和摘要，拒绝其他包及非升级版本；检查 FileProvider 目录边界及后台检查合并且不阻塞主线程。
- 4 个 Python 测试通过：版本递增规则、清单匹配实际 APK、发布处理中重试、终态失败及包名错误拒绝。
- Debug / Release Lint 及签名压缩构建通过。发布 APK 使用原来的 CNote 证书，`apksigner verify` 通过。
- 在本次隔离模拟器上将原 0.1.0 release 直接覆盖安装为 0.1.1，包名/版本正确；打开旧 `Release-Save-Test` 笔记后正文 `Offline-Save-Works` 完整保留，截图见 `../screenshots/upgrade-preserved-note.png`。
- 工作流 YAML、嵌入的 Python 和发布脚本语法检查通过。

## 待提交后启用

GitHub 仓库最初为空，尚无提交。需确认提交信息后推送 CNote 源码，才能由 Actions 自动运行并提供 GitHub Release 更新镜像。`cyeam_web/conf/app_updates.json` 已新增 CNote 的真实蒲公英短链接；该配置尚未部署，需提交并经网站已有发布流程上线后才启用主网关。本次未执行 fly 部署。

蒲公英首版已真实发布；Actions 云端运行、生产网关及未来版本的在线下载到系统安装器的完整链路尚待代码上线后联调。已有 APK 校验、系统下载任务恢复逻辑及覆盖安装数据保留检查不能替代完整线上联调。

APK SHA-256：`e8ce3ef672219c44dd7a6a58d602ff48559c439b1bb09a9bdaa2b7fa60b159d1`。
