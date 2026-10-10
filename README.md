# 图片编辑 · Android

中文深色界面的原生图片编辑 App，支持 Android 12（API 31）及以上。使用 Kotlin、Jetpack Compose 和 OpenGL ES 2.0，所有图片编辑在设备本地完成。

版本修改与验证记录见 [修改记录](CHANGELOG.md)。后续代码、配置、文档或测试修改完成后，持续补充日期、内容及必要的验证结果，并保留历史。

## 功能

- 调色：曝光、亮度、对比度、饱和度、色温、色调、阴影、高光；选择参数后使用单条滑杆调整，支持单项重置。
- 曲线：RGB 总曲线和 R/G/B 通道曲线，点击添加控制点、拖动调整、长按删除内部点。
- HSL：红、橙、黄、绿、青、蓝、紫、洋红八色；选择颜色与色相、饱和度或明度后使用单条滑杆调整。
- 单段文字水印：系统字体，支持中文、多行、颜色、字号和图片内拖动定位。
- 裁剪：自由、原比例、1:1、4:3、16:9；移动、边角调整、90°旋转、水平/垂直翻转。
- 手势级撤销/重做（30 步）、工具应用/取消、按住查看原图。
- v1.1：自动保存最近一次已应用的编辑，重启后从首页继续或删除草稿。
- 双指缩放与平移、双击切换适配/100%，通过原图区域解码查看高清细节。
- 图片上的可折叠 RGB/亮度直方图浮层（256 档）、高光红色/阴影蓝色溢出提示。
- v1.2：个人调色预设，支持保存、改名、删除、预览应用及撤销；调色参数可跨照片复制粘贴。
- JPEG/PNG 导出：原尺寸、长边 2048/1080 或自定义 1–20000 像素；JPEG 质量 50–100（默认 95）。保存到相册 `Pictures/ImageEditApp` 并支持系统分享。

## 编辑操作与布局

- 窄屏使用 56dp 顶栏与 64dp 底部工具栏，展开的工具面板最多占 240dp，且不超过可用编辑区高度的 44%；图片画布周边留白缩至 12dp。可用宽度至少 600dp 时采用左侧工具栏、中央图片、右侧参数面板的左右布局。
- 再次点击当前工具或点击“收起”可隐藏参数区、扩大图片查看区域；收起只改变布局，保留未应用的调整及当前参数选择，重新展开后可以继续编辑、应用或取消。
- 文字内容在独立输入窗口中编辑，点击“确认”后才更新图片，内容变化合并为一步撤销；关闭输入窗口或取消输入保留原文字。颜色、字号与图片内拖动仍在文字工具中调整。
- “适配”随可用区域重新适配；手动缩放和平移、100% 查看在面板展开或收起、普通工具切换及横竖屏布局变化时保留实际显示倍率，并在图片边界允许的范围内保持查看中心。普通预览与裁剪预览分别保留查看状态。
- 直方图和提示显示在图片浮层中，展开直方图不再挤占图片的布局高度。工具“应用”、切换工具自动应用、工具“取消”及手势级撤销的规则保持一致。

本轮布局与交互验证包含 43 项 JVM 测试，以及 API 31、API 36 各 76 项设备测试；两台模拟器另外在 360×640dp 竖屏及 640×360dp 横屏各通过 6 项布局交互测试，包含真实键盘下中文多行输入和操作按钮位置检查。结果与截图见 [验证说明](docs/VALIDATION.md)。

## 安装

本轮界面优化的 Debug 验证包位于 `dist/ImageEditApp-debug.apk`，版本为 1.2.0 / versionCode 3。此前正式签名的 Release 包位于 `dist/ImageEditApp-release.apk`，对应 [Release 验收记录](docs/RELEASE_VALIDATION.md)。安装包不随 GitHub 源码提交；从源码可按下方步骤重新构建。将 APK 文件传到 Android 12 或更高版本手机，打开并允许该文件来源安装即可。

Release 使用独立的新签名，与原 Debug 包不同，不能直接覆盖安装原 Debug 包；如需替换，先备份需要保留的内容，再手动卸载旧包，卸载会删除应用内草稿和个人预设。后续 Release 更新继续使用同一签名。

也可通过 Android SDK 安装：

```powershell
adb install -r .\dist\ImageEditApp-release.apk
```

## 构建

打开项目根目录。环境要求：JDK 17 或 21、Android SDK Platform 36、Build Tools 36.0.0。项目已包含 Gradle Wrapper；本机 `local.properties` 指向 `D:\Android\Sdk`。源码压缩包不包含本机配置，解压后请创建 `local.properties`，例如写入 `sdk.dir=D\:/Android/Sdk`，并按实际 SDK 路径修改。

```powershell
$env:JAVA_HOME = 'D:\Program Files\Java\jdk-21.0.11'
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。首次构建需要网络下载未缓存依赖。

固定版本：AGP 8.10.0、Gradle 8.12、Kotlin/Compose Compiler 2.2.10、Compose BOM 2026.02.01，JVM 目标 17，`minSdk=31`、`compileSdk=targetSdk=36`。

### Release 签名与 R8

本机已生成 `signing/ImageEditApp-release.jks`，密钥别名为 `imageeditapp-release`，使用 RSA 4096 位和 SHA256withRSA，证书有效期为 10000 天。签名配置和随机密码保存在根目录 `keystore.properties`，Gradle 从该文件读取凭据。请将密钥和配置一起安全备份，以便今后发布同签名更新；这两个文件以及整个 `signing/` 目录均已加入 Git 忽略规则，不包含在源码压缩包中。

在其他机器构建时，恢复签名密钥，复制 `keystore.properties.example` 为 `keystore.properties`，填写实际路径、别名和密码。`storeFile` 相对于项目根目录，路径使用 `/`。缺少签名配置时 Release 构建会失败，Debug 构建不需要该文件。

```powershell
$env:JAVA_HOME = 'D:\Program Files\Java\jdk-21.0.11'
.\gradlew.bat assembleRelease testReleaseUnitTest lintRelease
```

签名 APK 输出到 `app/build/outputs/apk/release/app-release.apk`。Release 配置启用 `isMinifyEnabled` 和 `isShrinkResources`，使用 `proguard-android-optimize.txt` 与 `app/proguard-rules.pro`；AGP 8.10 默认采用 R8 full mode，配置依据见 [Android R8 文档](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)。当前业务没有需要额外保留的反射序列化类，ViewModel 构造函数由 Lifecycle 的 consumer rules 保留。

每次发布应将 `app/build/outputs/mapping/release/mapping.txt` 与对应 APK 一起归档，用于还原混淆后的崩溃堆栈。本次交付映射文件为 `dist/ImageEditApp-release-mapping.txt`，核验记录见 `dist/ImageEditApp-release-info.txt` 和 [Release 验证说明](docs/RELEASE_VALIDATION.md)。

## 设备测试

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb -s emulator-5556 install -r .\app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 install -r .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r com.kang.imageeditapp.test/androidx.test.runner.AndroidJUnitRunner
```

同时连接多台设备时使用明确的 `-s`，或设置 `ANDROID_SERIAL` 后执行 `connectedDebugAndroidTest`。本次验收使用 API 31 和 API 36 模拟器，结果见 [验证说明](docs/VALIDATION.md)。

## 界面截图

本轮小屏与横屏布局、中文多行输入及真实键盘截图见 [布局验收](docs/VALIDATION.md#布局截图)。既有功能截图保留在 `docs/screenshots/home.png`、`editor.png`、`presets.png` 和 `export.png`。

## 实现要点

- `PhotoSource` 保存原始编码尺寸与 EXIF 方向；`EditRecipe` 保存不可变编辑参数，原图副本位于应用持久目录，草稿使用带版本号的原子 JSON 和缩略图。
- ViewModel 通过 StateFlow 管理界面；预览请求合并，只发布最新版本；GL 渲染和释放统一在专用线程。
- 预览和导出共用 shader、保形曲线 LUT 和水印布局。原尺寸导出按区域读取，最多 1024 像素分块渲染，处理设备纹理大小限制。
- 缩放状态独立于编辑参数和撤销记录，区分适配与手动查看，布局变化时按实际像素倍率和图片中心重映射，并支持 Activity 重建恢复；100% 对应一个原图像素/一个屏幕像素。可见区域高清渲染复用同一 shader、变换和水印布局。
- 直方图统计完整裁剪预览的非透明像素，在添加水印前计算；不随查看区域变化。亮度采用当前 sRGB 显示值的 Rec.709 权重。任一 RGB 通道为 255 显示高光提示，三个通道均为 0 显示阴影提示；提示不进入导出。
- 文字位置以最终裁剪画布归一化保存，字号相对于短边；重新裁剪保留相对位置。
- PNG 保留透明度；JPEG 将透明区域合成白底。MediaStore 写入期间标为 pending，完成后发布，失败清理文件。
- 缩小导出直接按目标尺寸进行区域解码和分块渲染，保持裁剪比例且不放大小图，避免先创建完整原尺寸 Bitmap。导出冻结点击保存时的图片、编辑参数、尺寸和质量。
- 个人预设和应用内调色剪贴板使用独立的私有原子存储，跨启动保留；只包含调色、RGB/独立通道曲线和 HSL。套用到新图时保留该图的裁剪、旋转、翻转及文字。
- 使用系统照片选择器，缺少照片选择器时由 Activity contract 回退到系统文件选择器；无需整库读取权限或网络权限。

## 当前约定

v1.2 为单图、sRGB、8 位输出，保留最近一份草稿。点击工具“应用”、切换工具、撤销或重做后自动保存；未应用的面板预览可取消。关闭编辑回到首页保留已应用状态，重启后点击“继续编辑”恢复。新图片成功导入后替换旧草稿，导入取消或失败保留旧内容。删除草稿清理私有副本，不影响系统相册原图和导出文件；撤销历史仅在当前会话保留。

在“预设”面板保存当前调色，名称为 1–24 个 Unicode 字符，最多保留 30 个个人预设，不接受同名。点击预设或“粘贴调色”预览效果，应用后保存，取消可恢复；每次套用形成一步撤销。保存预设和复制调色会独立保存当前预览参数，取消工具不会撤销这两项保存。应用内部保留一份调色剪贴板，重启后仍可粘贴；系统剪贴板内容不受影响。删除草稿不会删除个人预设，卸载或清除应用数据会删除私有草稿、预设和调色剪贴板。

导出默认原尺寸 JPEG、质量 95；本次运行中再次打开导出时保留上次有效选择的设置。自定义尺寸表示裁剪后的长边，短边等比取整且至少为 1 像素。比原图大的选择仍输出原尺寸。PNG 质量由无损编码决定，JPEG 质量滑杆只在 JPEG 模式显示。

直方图和溢出提示基于最多 1600 像素长边的完整裁剪预览，用于观察当前 sRGB 成片；高清区域单独读取原图，局部溢出遮罩也按高清像素生成。GIF 动画图片不接受导入；其他格式以设备解码能力为准，JPEG/PNG 为完整验证的输入与输出格式。

分块导出仍需一个目标尺寸的最终 Bitmap。极大原尺寸图片超过设备可用内存时会显示中文原因并保留编辑状态，可选择较小的导出尺寸或缩小裁剪范围后重试。依赖版本按方案固定，Lint 的新版本提示不影响构建。
