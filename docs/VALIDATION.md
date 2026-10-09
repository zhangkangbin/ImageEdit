# 验证结果

验收日期：2026-10-09（Asia/Shanghai）。交付版本：1.0.0 Debug。

## 构建与静态检查

`assembleDebug`、`testDebugUnitTest`、`lintDebug`、`assembleDebugAndroidTest` 均成功。Lint 为 **0 错误、9 条警告**，警告为按方案固定依赖版本的新版本提示和 Bitmap KTX 风格建议。

12 项 JVM 单元测试全部通过：

| 类别 | 数量 | 验证内容 |
| --- | ---: | --- |
| GeometryTest | 4 | 八种 EXIF 映射、裁剪与旋转翻转的全部组合往返、尺寸交换、裁剪边界 |
| RecipeHistoryTest | 5 | 完整状态撤销重做、新分支清理、30 步限制、取消检查点、无变化手势 |
| CurveLutTest | 3 | 默认曲线精确恒等、独立通道与重置、保形曲线控制点和范围 |

## 设备验收

| 系统 | 设备 | 结果 | 最后完整运行耗时 |
| --- | --- | --- | ---: |
| Android 12 / API 31 | ImageEdit_API31 / emulator-5554 | 12/12 通过 | 24.563 秒 |
| Android 16 / API 36 | ImageEdit_API36 / emulator-5556 | 12/12 通过 | 27.112 秒 |

两台使用 Google APIs x86_64 系统镜像、WHPX 和 SwiftShader。安装与启动均成功，未发现 AndroidRuntime 崩溃。原始 instrumentation 输出为 `test-api31.txt`、`test-api36.txt`。

9 项图片引擎测试覆盖：

- 八种 EXIF 与用户旋转翻转的象限和尺寸。
- 同分辨率预览与导出的调色像素一致性，通道误差不超过 2。
- 半透明 PNG 的透明度，以及 JPEG 白底合成。
- 2056×1080 图片跨 1024 像素分块边界的输出。
- 超大原尺寸输出在分配前触发内存保护。
- 中文水印在不同分辨率下保持相对位置。
- 损坏图片导入报错。
- HSL 红色色相在 359°/1° 的连续性。
- 编码失败后没有遗留 pending 相册条目。

3 项真实 Compose 界面测试覆盖：

- 导入测试图片→调色滑杆→撤销重做→面板取消→曲线增点与拖动→HSL→中文文字与拖动→裁剪比例、旋转、移动、边角缩放和翻转→应用→JPEG/PNG 保存；核对实际文件 MIME 与输出尺寸，并启动系统分享面板。
- 打开并取消照片选择器保留编辑；失败导入显示错误且保留当前图片与参数。
- 系统返回取消当前工具，结束本次编辑需要确认。

另外在 API 36 通过首页按钮打开真实 Google Photo Picker，从新建的模拟器图库选择 640×480 测试图并成功返回编辑器。截图 `screenshots/home.png` 和 `screenshots/editor.png` 已目视检查，均为 1080×2400，无重叠或异常裁切。

## APK 核验

- 包名：`com.kang.imageeditapp`
- 最低系统：API 31；target/compile SDK：36。
- 版本：1.0.0 / versionCode 1。
- 类型：Debug，可安装测试。
- apksigner 验证通过，APK Signature Scheme v2 有效。
- 文件大小：30,413,202 字节。
- SHA-256：`6EC6C0F0A69A114E24C8B2D87A6C6D240ECF195BF5FC5559F35037AF7B3FFC0D`

## 实际边界

导出为 sRGB、8 位 JPEG/PNG。分块渲染避免整张原图及完整 GPU 纹理的重复占用，但系统编码仍需要一个完整输出 Bitmap，极大图片超出当前设备可用内存时会提示失败并保留编辑。首版未提供跨次启动的草稿管理。实机的 GPU 与系统图库差异尚需日常使用反馈；本次安装和完整自动化验收覆盖上述两版模拟器。
