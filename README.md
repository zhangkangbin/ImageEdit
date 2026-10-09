# 图片编辑 · Android

中文深色界面的原生图片编辑 App，支持 Android 12（API 31）及以上。使用 Kotlin、Jetpack Compose 和 OpenGL ES 2.0，所有图片编辑在设备本地完成。

## 功能

- 调色：曝光、亮度、对比度、饱和度、色温、色调、阴影、高光；单项重置。
- 曲线：RGB 总曲线和 R/G/B 通道曲线，点击添加控制点、拖动调整、长按删除内部点。
- HSL：红、橙、黄、绿、青、蓝、紫、洋红八色的色相、饱和度、明度。
- 单段文字水印：系统字体，支持中文、多行、颜色、字号和图片内拖动定位。
- 裁剪：自由、原比例、1:1、4:3、16:9；移动、边角调整、90°旋转、水平/垂直翻转。
- 手势级撤销/重做（30 步）、工具应用/取消、按住查看原图。
- 原尺寸 JPEG（质量 95）/PNG 导出，保存到相册 `Pictures/ImageEditApp` 并支持系统分享。

## 安装

本机交付 APK 位于 `dist/ImageEditApp-debug.apk`，不随 GitHub 源码提交；从 GitHub 获取源码后，可按下方构建步骤生成 APK。将 APK 文件传到 Android 12 或更高版本手机，打开并允许该文件来源安装即可；它是用于本地测试的 Debug 包。

也可通过 Android SDK 安装：

```powershell
adb install -r .\dist\ImageEditApp-debug.apk
```

## 构建

打开项目根目录。环境要求：JDK 17 或 21、Android SDK Platform 36、Build Tools 36.0.0。项目已包含 Gradle Wrapper；本机 `local.properties` 指向 `D:\Android\Sdk`。源码压缩包不包含本机配置，解压后请创建 `local.properties`，例如写入 `sdk.dir=D\:/Android/Sdk`，并按实际 SDK 路径修改。

```powershell
$env:JAVA_HOME = 'D:\Program Files\Java\jdk-21.0.11'
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。首次构建需要网络下载未缓存依赖。

固定版本：AGP 8.10.0、Gradle 8.12、Kotlin/Compose Compiler 2.2.10、Compose BOM 2026.02.01，JVM 目标 17，`minSdk=31`、`compileSdk=targetSdk=36`。

## 设备测试

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb -s emulator-5556 install -r .\app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 install -r .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r com.kang.imageeditapp.test/androidx.test.runner.AndroidJUnitRunner
```

同时连接多台设备时使用明确的 `-s`，或设置 `ANDROID_SERIAL` 后执行 `connectedDebugAndroidTest`。本次验收使用 API 31 和 API 36 模拟器，结果见 `docs/VALIDATION.md`。

## 界面截图

实际模拟器截图位于 `docs/screenshots/home.png` 和 `docs/screenshots/editor.png`；编辑截图通过系统照片选择器导入测试图后取得。

## 实现要点

- `PhotoSource` 保存原始编码尺寸与 EXIF 方向；`EditRecipe` 保存不可变编辑参数，图片导入为仅本会话使用的私有副本。
- ViewModel 通过 StateFlow 管理界面；预览请求合并，只发布最新版本；GL 渲染和释放统一在专用线程。
- 预览和导出共用 shader、保形曲线 LUT 和水印布局。原尺寸导出按区域读取，最多 1024 像素分块渲染，处理设备纹理大小限制。
- 文字位置以最终裁剪画布归一化保存，字号相对于短边；重新裁剪保留相对位置。
- PNG 保留透明度；JPEG 将透明区域合成白底。MediaStore 写入期间标为 pending，完成后发布，失败清理文件。
- 使用系统照片选择器，缺少照片选择器时由 Activity contract 回退到系统文件选择器；无需整库读取权限或网络权限。

## 当前约定

首版为单图、sRGB、8 位输出；使用当前会话状态，旋转屏幕保留编辑，重新启动后的草稿管理尚未提供。GIF 动画图片不接受导入；其他格式以设备解码能力为准，JPEG/PNG 为完整验证的输入与输出格式。

分块导出仍需一个完整的最终 Bitmap。极大图片超过设备可用内存时会显示中文原因并保留编辑状态，可缩小裁剪范围后重试。依赖版本按方案固定，Lint 的新版本提示不影响构建。
