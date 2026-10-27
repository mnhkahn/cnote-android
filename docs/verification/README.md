# 2026-10-27 冲突与图片补齐验证

- Android 36 / Google APIs x86_64 模拟器 `cnote_android_test`（`ANDROID_SERIAL=emulator-5554`）：17 项 AndroidJUnitRunner 测试全部通过。
- 新增真实 WebView / SQLite / 剪贴板测试覆盖逐段显式选择、合并预览、过期本地修订拒绝、重新打开对比、保存结果反馈，以及图片触屏选择、放大预览、返回键关闭、PNG content URI 读取、剪切与撤销、系统剪贴板图片回贴。
- 存储测试覆盖旧版大改确认标记清理、空内容仍待同步、云端 ETag 和本地修订不符时保留双方内容、合并后基线与 ETag 更新。
- JVM：10 项测试通过；本地 Chromium / WebKit 触屏浏览器：9 项测试通过；发布脚本：4 项 Python 测试通过。
- `lintDebug` 为 0 errors，仍有已有的 KTX、国际化等建议；`assembleDebug` 和测试 APK 构建成功。
- 浏览器测试与差异资源一致性检查已加入 Android CI；差异库随 APK 打包，不依赖 CDN。
- 一轮测试期间新连接的实体平板被 Gradle 自动发现，安装被取消（`INSTALL_FAILED_USER_RESTRICTED`），未安装成功。最终测试显式限定上述模拟器。
- 未使用真实 Microsoft 账号进行云端端到端同步；保留原有 ETag 条件写入，实现对已下载冲突快照的版本校验。本次未发布 APK。

最终模拟器测试输出见 `conflict-image-device-tests.txt`。

---

# 本地验证

2026-10-22，macOS x86_64，Android Studio JBR 21；独立 Android 36 / Google APIs x86_64 模拟器 `cnote_android_test`，未操作实体设备或其他模拟器。

- `testDebugUnitTest`：3 个 JVM 测试通过。
- `lintDebug`、`lintRelease`：0 errors；仍有依赖升级、国际化/KTX 建议等 warnings。
- `assembleDebug`、`assembleDebugAndroidTest`、压缩并签名的 `assembleRelease` 构建成功。
- `AndroidJUnitRunner`：10 个真实设备环境测试通过，输出见 `device-tests.txt`。其中 8 个数据库/Keystore 测试和 2 个实际 WebView 编辑器测试。
- 图标所需的原生字形均存在；编辑器截图已核对，见 `../screenshots/editor.png`。
- Google 配置只包含 `com.cyeam.cnote` 一个 Android 客户端。release 使用 APK v2 签名，`apksigner verify` 通过；签名私钥与密码处于忽略的 `.signing/` 目录。

编辑器测试验证了切走后立即切回时复用同一 DOM、未落盘草稿继续保存、Activity 重建时保留同一编辑器和未保存输入、HTML 清理、后台更新使旧缓存失效而不抢走当前笔记，以及未保存草稿不被缓存失效覆盖。

测试还记录 40 次已缓存笔记交替切换的同步 JS 执行耗时。不同轮次中位数约 4–6 ms，P95 约 10–63 ms；测试时主机同时编译/R8 压缩，波动明显。这仅衡量编辑器切换路径，不包含触摸到显示的完整延迟，也不代表真机或 macOS 备忘录的对比结果。

尚未使用真实 Microsoft 账号完成登录/云端同步，也未登录 Firebase 控制台验证服务器事件入库；GitHub Actions 工作流已配置且 YAML 通过解析，尚未推送运行。Android API 26 的运行时兼容性尚未实机验证，设备需可用且更新的 Android System WebView。

最终 release 包安装到上述隔离模拟器后，已通过原生界面创建 `Release-Save-Test` 笔记、输入 `Offline-Save-Works`，观察到“已保存到本机”，随后强制停止并重新启动进程，列表仍显示该笔记。R8 处理后的本地编辑器、原生桥接与图标正常。

交付 APK：`dist/CNote-0.1.0.apk`，1495482 bytes，SHA-256：`992d47a089cdafb45d16190a33f83990a257a20e63845b9cab9619b89f3f72c9`。

重启后打开上述笔记，正文 `Offline-Save-Works` 完整恢复，截图见 `../screenshots/release-restored.png`。验证结束后已停止本次创建的隔离模拟器。
