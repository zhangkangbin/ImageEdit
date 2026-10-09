# Release 构建与核验

日期：2026-10-09（Asia/Shanghai）。版本：1.2.0 / versionCode 3。

## 配置

- `app/build.gradle.kts` 新增独立 Release 签名配置，从根目录 `keystore.properties` 读取密钥路径、类型、别名和密码；缺少配置时 Release 构建失败，Debug 可独立构建。
- 新建本机 `signing/ImageEditApp-release.jks`，别名 `imageeditapp-release`，RSA 4096 位、SHA256withRSA、证书有效期 10000 天。密码由安全随机数生成。密钥和密码配置均被 Git 忽略；源码只包含配置示例。
- Release 开启 `isMinifyEnabled=true` 和 `isShrinkResources=true`，使用 `proguard-android-optimize.txt` 与项目规则文件。R8 版本 8.10.21，采用 AGP 8.10 默认的 full mode。
- 业务 JSON 使用明确字段读写，Lifecycle 自带 `AndroidViewModel(Application)` 构造函数保留规则，无需宽泛保留整个应用包。

## 构建检查

```powershell
$env:JAVA_HOME = 'D:\Program Files\Java\jdk-21.0.11'
.\gradlew.bat assembleRelease testReleaseUnitTest lintRelease --console=plain
```

构建成功，35 项 JVM 单元测试全部通过，无失败、错误或跳过。Lint 为 0 错误、12 条警告，仍为固定依赖版本提示和 Bitmap KTX 建议。JVM 测试验证逻辑回归，执行的是 Release 编译产物。

临时移走本机签名配置并在检查后恢复，验证 `assembleDebug --dry-run` 仍可正常配置，`assembleRelease` 会在独立的签名配置校验任务中明确失败，避免缺配置时生成未签名的 Release 包。恢复配置后再次构建 Release 成功。

最终 APK 的 `apksigner verify --verbose --print-certs` 与 `zipalign -c -P 16 4` 均通过，APK Signature Scheme v2 有效，RSA 4096 位签名证书与新生成的本机证书一致。Manifest 核验确认包名、版本、最低 API 31、目标 API 36，未启用 debuggable。

## 最终 Release 实际运行

使用独立新建的 API 36 模拟器安装最终签名、混淆的 APK，未添加测试用保留规则，未开启 debuggable。已通过以下真实流程：

- 安装和冷启动，ViewModel 创建正常；从系统 PhotoPicker 导入 640×480 测试图，OpenGL 预览正常。
- 调整曝光并应用，界面显示 +1.0 EV，草稿记录实际值为 1.0180275。
- 分别导出 JPEG、PNG，MediaStore 中均为 640×480、`Pictures/ImageEditApp/`、`is_pending=0`；拉回两个文件并成功解码，像素采样确认调色生效。
- 强制停止后重新启动，从首页继续草稿；进程 PID 已更换，重启前后完整 recipe 和 source 相同，曝光仍为 1.0180275，枚举持久化键保持正确。
- crash buffer 为空，测试结束后关闭独立模拟器。原有模拟器和物理设备未接受安装、卸载或数据清理。

详细证据位于本机 `dist/release-validation/SMOKE-REPORT.md`，包括截图、命令日志、导出图片和重启前后 JSON。本次执行最终 Release 的核心运行检查；既有完整仪器测试仍为 Debug 验收记录，未重跑 API 31 或实体设备 Release 回归。

## 交付文件

| 文件 | 说明 |
| --- | --- |
| `dist/ImageEditApp-release.apk` | 新签名的 R8 Release 安装包，2,361,620 字节（约 2.25 MiB） |
| `dist/ImageEditApp-release-mapping.txt` | 对应该安装包的 R8 映射，用于崩溃堆栈还原 |
| `dist/ImageEditApp-release-info.txt` | 构建、签名与哈希核验记录 |
| `signing/ImageEditApp-release.jks` | 本机私钥，需要与 `keystore.properties` 一起安全备份 |
| `signing/ImageEditApp-release-certificate.pem` | 导出的公开证书 |

APK SHA-256：`BD8B2584B1914144AA8FD4BF5FA1939DC31037469ADADEE3FC71EAFD41E76E7A`。

证书 SHA-256：`67115fc16e1d2e93cfdfe023dbb791b4c2346cba60015adb8eda16f19de1a790`。

映射文件 SHA-256：`BC738BCD4C357BE58DEDF0960936AA396D8D29B8BBC545D305C93A46C683159F`。

原 Debug APK 为 30,865,862 字节；本次 Release 包包含四种 ABI 的运行依赖。包名与 Debug 相同，签名不同，不能直接覆盖旧 Debug 安装；卸载旧包会删除应用私有草稿和预设。
