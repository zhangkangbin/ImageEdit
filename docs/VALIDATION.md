# 验证结果

验收日期：2026-10-09（Asia/Shanghai）。交付版本：1.1.0 Debug / versionCode 2。

## 构建与静态检查

`assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 均成功。Lint 为 **0 错误、12 条警告**，包括固定依赖版本的新版本提示和 Bitmap KTX 风格建议。`git diff --check` 通过。

21 项 JVM 单元测试全部通过：

| 类别 | 数量 | 验证内容 |
| --- | ---: | --- |
| GeometryTest | 4 | 八种 EXIF、裁剪和旋转翻转组合的坐标往返与尺寸 |
| RecipeHistoryTest | 5 | 撤销重做、30 步限制、取消检查点和无变化手势 |
| CurveLutTest | 3 | 默认曲线、独立通道、保形插值和重置 |
| PixelAnalysisTest | 4 | RGB/亮度 256 档、透明像素过滤、高光阴影边界、空输入 |
| PreviewViewportTest | 5 | 适配与原生倍率、缩放中心、平移边界、可见区域和坐标映射 |

## API 31 与 API 36 设备验收

| 系统 | 设备 | 结果 | 最后完整运行耗时 |
| --- | --- | --- | ---: |
| Android 12 / API 31 | ImageEdit_API31 / emulator-5554 | 43/43 通过 | 79.564 秒 |
| Android 16 / API 36 | ImageEdit_API36 / emulator-5556 | 43/43 通过 | 83.559 秒 |

两台使用 Google APIs x86_64 镜像、WHPX 和 SwiftShader。Debug APK 安装和启动成功，验收后未发现 AndroidRuntime 崩溃。原始日志为 `test-v11-api31.txt` 和 `test-v11-api36.txt`。

| 测试类 | 数量 | 覆盖 |
| --- | ---: | --- |
| DraftRepositoryTest | 9 | 所有编辑参数和缩略图持久恢复、版本/损坏数据、替换失败、删除、私有路径校验、元数据限制 |
| EditorDraftLifecycleTest | 7 | 新 ViewModel 恢复、应用后立即清理时的最终保存、取消/放弃面板、应用/撤销/重做、关闭恢复删除、导入/保存失败与重试 |
| EditorV11UiTest | 8 | 草稿首页操作、直方图/提示、100% 和双击、两指不产生编辑、缩放后的文字/裁剪坐标、小裁剪原图高清、透明图片单次合成、快速参数与 ROI 更新一致性 |
| PreviewAnalysisIntegrationTest | 7 | 预水印统计与遮罩、裁剪全图统计、32 种 EXIF/旋转/翻转组合、4096px 原图细节、中文水印、窄 ROI、纹理限制 |
| EditorUiTest | 3 | 导入→各工具编辑→JPEG/PNG 保存→系统分享、照片选择取消/失败、返回和工具取消 |
| RenderIntegrationTest | 9 | 调色预览/导出一致、EXIF、透明 PNG/白底 JPEG、分块接缝、超大图保护、中文水印、红色色相边界及保存失败清理 |

存储故障采用阻塞原子文件临时写入路径的方式注入，验证旧草稿和原图不丢失，当前可见编辑保留，并能恢复保存。该测试验证写入失败路径，未实际耗尽模拟器磁盘。

## 真实进程重启和界面检查

另在 API 36 从真实 Google Photo Picker 导入 640×480 测试图，应用曝光修改后执行 `am force-stop`，重新启动应用。首页显示最近草稿，点击继续编辑成功；核对原图文件名和完整编辑参数与重启前一致。记录见 `test-v11-process-restart.txt`。

已更新并目视检查 `screenshots/home.png` 和 `screenshots/editor.png`：首页展示草稿入口，编辑器展示 RGB 直方图、溢出提示开关和缩放控件。截图为实际 1080×2400 模拟器界面，没有重叠或异常裁切。

## APK 核验与交付

- 包名：`com.kang.imageeditapp`；最低 API 31，target/compile SDK 36。
- 版本：1.1.0 / versionCode 2；Debug 安装包。
- apksigner 验证通过，APK Signature Scheme v2 有效。
- APK 大小：30,608,322 字节。
- SHA-256：`251CA35A807C70CE9C314E27B425BD27F00368F35ED3407AE03868672CF58284`。
- 本机文件：`dist/ImageEditApp-debug.apk`、`dist/ImageEditApp-source.zip`；构建安装说明见项目 README。

## 实际边界

保持单图离线、sRGB、8 位 JPEG/PNG 输出。直方图基于最多 1600 像素长边的完整裁剪预览；100% 查看单独读取原图可见区域。遮罩和查看倍率不写入编辑参数、不进入导出。草稿保留最近一份已应用的编辑，撤销历史仅在当前会话保留。

分块导出仍需要一个完整最终 Bitmap；极大图片超出设备内存时提示原因并保留编辑。自动保存完成前被系统强制终止时恢复上一次成功写入的状态。API 31/36 已完成模拟器验收，实体设备 GPU 和图库差异尚未进行实测。
