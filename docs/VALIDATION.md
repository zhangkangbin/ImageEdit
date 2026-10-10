# 验证结果

## 2026-10-10 · 默认滤镜（未发布）

“预设”面板新增自然、鲜艳、暖阳、冷调、胶片、褪色、黑白、复古 8 款内置滤镜，以及仅恢复原始调色的“原色”入口。双列卡片支持滚动、风格说明和参数匹配高亮；复制、粘贴和保存入口保留在顶部，个人预设位于滤镜列表下方。

切换滤镜替换整组调色、曲线和 HSL，保留照片的裁剪、旋转、翻转及文字。点击预览后可应用、取消、撤销/重做，已应用效果沿用草稿保存机制；可继续调整并另存个人预设。内置目录不写入个人预设存储，不占用 30 个名额。

### 构建与单元测试

```powershell
$env:JAVA_HOME = 'D:\Program Files\Java\jdk-21.0.11'
.\gradlew.bat assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug --console=plain
```

最终构建成功，耗时 26 秒；45/45 项 JVM 测试通过，Lint 0 错误、12 条既有警告。新增 `BuiltInFiltersTest` 2 项，覆盖名称与参数有效性、不可变目录/曲线/HSL、切换替换完整调色及原色恢复；现有 43 项测试一同通过。构建日志保存在本机 `dist/build-builtin-filters.txt`。

### 设备回归

| 系统 | 结果 | 耗时 | 原始日志 |
| --- | --- | ---: | --- |
| API 31 / emulator-5554 | 24/24 通过 | 60.169 秒 | [API 31](test-builtin-filters-api31.txt) |
| API 36 / emulator-5556 | 24/24 通过 | 73.638 秒 | [API 36](test-builtin-filters-api36.txt) |

最终安装包验证 `EditorV12UiTest`（6 项）、`ColorWorkflowIntegrationTest`（8 项）及 `RenderIntegrationTest`（10 项），新增 4 项设备测试，原有 20 项个人预设、复制粘贴、草稿、渲染及导出检查一同通过。

新增检查通过真实点击遍历全部 9 个入口，核对选择高亮、切换不叠加、原色仅恢复调色，裁剪、旋转、双向翻转及中文文字保留；验证预览取消、应用后撤销/重做、保存为个人预设，以及应用前草稿保持原状态、应用后跨 ViewModel 恢复。渲染检查使用暗部、中间灰、高光、三种彩色和半透明色块，确认 9 组像素效果互不相同、黑白通道一致、冷暖/棕褐色调及褪色暗部提升正确，预览与导出误差不超过 2 个通道值，透明度误差不超过 1。

界面截图见 [默认滤镜入口](screenshots/builtin-filters.png)，保留原始屏幕尺寸。

### 小屏与横屏

| 系统 | 尺寸 | 结果 | 耗时 | 原始日志与截图 |
| --- | --- | --- | ---: | --- |
| API 31 | 360×640dp 竖屏 | 2/2 通过 | 18.788 秒 | [日志](test-builtin-filters-api31-portrait.txt)、[截图](screenshots/builtin-filters-portrait.png) |
| API 36 | 640×360dp 横屏 | 2/2 通过 | 22.807 秒 | [日志](test-builtin-filters-api36-landscape.txt)、[截图](screenshots/builtin-filters-landscape.png) |

使用 density 160 运行两个新增滤镜 UI 测试；竖屏底部面板和横屏侧栏均可滚动访问全部滤镜，保留复制、粘贴与保存入口，支持取消、应用、撤销/重做及另存个人预设。目视检查基线、竖屏与横屏原始截图。验收后两台模拟器均恢复 1080×2400、density 420、自动旋转开启（`accelerometer_rotation=1`）和 `user_rotation=0`。

### 当前安装包

本轮 Debug APK 已更新到 `dist/ImageEditApp-debug.apk`，版本 1.2.0 / versionCode 3，30,456,802 字节，SHA-256 为 `DA55F57BD8A34BB7A6EEC972440E8A11C093DE161DD3549D8D4C27E2073F6B67`。`apksigner verify --verbose` 通过（v2 签名有效），`zipalign -c -P 16 4` 通过。

README、修改记录和本文的本地链接核对通过，`git diff --check` 通过。

以下保留此前各轮验收历史，测试结果、截图和 APK 哈希对应当时构建。

## 2026-10-10 · 工具面板展开时隐藏标题栏（未发布）

本轮构建版本为 1.2.0 / versionCode 3 Debug。展开调色、曲线、HSL、预设、文字或裁剪的参数面板时，顶部 56dp 标题栏自动隐藏，为图片预览腾出空间；收起面板、应用或取消工具后恢复标题栏，更换图片和导出入口重新显示。收起仅改变界面布局，保留未应用的参数和当前选择。

宽屏标题栏隐藏时，直方图与溢出提示入口移到图片预览浮层；标题栏恢复后，入口回到顶栏。撤销、重做和按住查看原图继续位于预览右下角。适配模式随可用区域变化，手动缩放与 100% 查看保留实际像素倍率，查看中心按图片边界约束。

### 构建、单元测试与静态检查

```powershell
$env:JAVA_HOME = 'D:\Program Files\Java\jdk-21.0.11'
.\gradlew.bat assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug --console=plain
```

四项 Gradle 任务在同一次运行中成功，耗时 33 秒；43/43 项 JVM 测试通过，其中 `PreviewViewportTest` 为 13 项。Lint 为 0 错误、12 条警告。

### 相关界面回归

| 系统 | 回归范围 | 结果 | 耗时 | 原始日志 |
| --- | --- | --- | ---: | --- |
| API 31 / emulator-5554 | 四项 UI 测试类 | 21/21 通过 | 143.932 秒 | [API 31](test-editor-header-api31.txt) |
| API 36 / emulator-5556 | 四项 UI 测试类 | 21/21 通过 | 185.485 秒 | [API 36](test-editor-header-api36.txt) |

范围为 `EditorLayoutUiTest`（6 项）、`EditorUiTest`（3 项）、`EditorV11UiTest`（8 项）和 `EditorV12UiTest`（4 项）。调色、曲线、HSL、预设、文字与裁剪展开时均验证标题栏隐藏，工作区回收精确 56dp；收起、应用或取消后标题栏及更换图片、导出入口恢复。参数选择、未应用编辑、100% 查看倍率、高清区域与查看中心保持；宽屏直方图和溢出入口随标题栏切换位置，每项入口唯一且可操作。

既有撤销、重做、原图按住对比、曲线控制点真实拖动、文字/裁剪坐标映射及真实键盘上的中文多行输入检查一同通过。

### 小屏横竖屏与实际截图

| 系统 | 测试尺寸 | 布局回归 | 耗时 | 原始日志 |
| --- | --- | --- | ---: | --- |
| API 31 | 360×640dp | 6/6 通过 | 57.589 秒 | [竖屏](test-editor-header-api31-small-portrait.txt) |
| API 31 | 640×360dp | 6/6 通过 | 52.894 秒 | [横屏](test-editor-header-api31-small-landscape.txt) |
| API 36 | 360×640dp | 6/6 通过 | 73.381 秒 | [竖屏](test-editor-header-api36-small-portrait.txt) |
| API 36 | 640×360dp | 6/6 通过 | 59.962 秒 | [横屏](test-editor-header-api36-small-landscape.txt) |

两台模拟器使用 density 160 的尺寸覆盖运行 `EditorLayoutUiTest`，验证六工具标题栏隐藏与恢复、工作区高度、参数与倍率保持、分析入口迁移及曲线拖点、真实中文多行输入。

已目视检查以下 API 36 原始截图，保留完整屏幕，未裁切或重绘：

- [展开工具后隐藏标题栏](screenshots/editor-header-expanded.png)、[收起工具后恢复标题栏](screenshots/editor-header-collapsed.png)：基线 1080×2400。
- [360×640 竖屏](screenshots/editor-header-portrait.png)、[640×360 横屏](screenshots/editor-header-landscape.png)：小屏布局。

宽屏收起状态、全部六项工具和真实键盘的补充原始证据保存在本机 `dist/header-layout-validation/`。验收结束后已核对两台模拟器恢复到 1080×2400、density 420、自动旋转开启（`accelerometer_rotation=1`）、`user_rotation=0`；硬件键盘显示设置恢复为 API 31 的 1、API 36 的 0。

### 当前构建包核验

本轮构建与设备验收使用的同一包已归档为 `dist/ImageEditApp-debug.apk`，30,379,405 字节，SHA-256 为 `2E7D23637DDEA1F52F533374B81C5AF5BEA54982B0F663D27271A4330EF41740`。`apksigner verify --verbose` 通过（v2 签名有效），`zipalign -c -P 16 4` 通过。

以下保留标题栏条件隐藏之前的验收历史，测试结果、截图和 APK 哈希对应各轮当时构建。

## 2026-10-10 · 图片操作按钮位置调整（未发布）

本轮构建版本为 1.2.0 / versionCode 3 Debug。图片右下角的倍率文字、“适配”和“100%”按钮已移除；撤销、重做、按住查看原图改为图片预览右下角浮层中的三个 48dp 按钮，顶栏相应简化。双指缩放、平移及双击切换适配/100% 的操作保留，编辑配方与撤销规则沿用原有格式。

原图对比按下时消费指针事件，并使用 `try/finally` 在松手、取消或手势终止时恢复编辑效果。图片画布新增 `PreviewPixelScaleKey` 与 `PreviewViewportModeKey` 语义属性，供界面验收读取实际像素倍率和查看模式，不增加可见倍率控件。

### 已完成的构建与单元测试

```powershell
$env:JAVA_HOME = 'D:\Program Files\Java\jdk-21.0.11'
.\gradlew.bat assembleDebug testDebugUnitTest --console=plain
.\gradlew.bat assembleDebugAndroidTest lintDebug --console=plain
```

四项 Gradle 任务成功；43/43 项 JVM 测试通过。Lint 为 0 错误、12 条警告。

### 相关界面回归

| 系统 | 回归范围 | 结果 | 耗时 | 原始日志 |
| --- | --- | --- | ---: | --- |
| API 31 / emulator-5554 | 四项 UI 测试类 | 21/21 通过 | 142.076 秒 | [API 31](test-preview-actions-api31.txt) |
| API 36 / emulator-5556 | 四项 UI 测试类 | 21/21 通过 | 179.425 秒 | [API 36](test-preview-actions-api36.txt) |

范围为 `EditorLayoutUiTest`（6 项）、`EditorUiTest`（3 项）、`EditorV11UiTest`（8 项）和 `EditorV12UiTest`（4 项）。验证按钮处于预览右下角、三项操作各只有一个实例、旧缩放控件缺席；撤销/重做可操作，按住对比时原图像素确有变化，松手恢复编辑像素，配方、撤销状态与视口不被对比修改。缩放测试改用真实双击与双指手势，避开右下角按钮，保留高清区域和文字/裁剪拖动坐标检查。

新布局的 [基线竖屏截图](screenshots/editor-actions-normal.png) 保留原始屏幕尺寸。图片画布尺寸不因按钮移动而改变。

### 小屏横竖屏

| 系统 | 测试尺寸 | 布局回归 | 耗时 | 原始日志 |
| --- | --- | --- | ---: | --- |
| API 31 | 360×640dp | 6/6 通过 | 55.024 秒 | [竖屏](test-preview-actions-api31-small-portrait.txt) |
| API 31 | 640×360dp | 6/6 通过 | 41.797 秒 | [横屏](test-preview-actions-api31-small-landscape.txt) |
| API 36 | 360×640dp | 6/6 通过 | 68.054 秒 | [竖屏](test-preview-actions-api36-small-portrait.txt) |
| API 36 | 640×360dp | 6/6 通过 | 50.007 秒 | [横屏](test-preview-actions-api36-small-landscape.txt) |

两台模拟器使用 density 160 的尺寸覆盖运行 `EditorLayoutUiTest`；按钮完整位于画布右下角，收起/展开及六项工具切换保持位置，点击对比不被画布识别为双击缩放。既有真实键盘、中文多行输入、曲线拖点和缩放保持检查一同通过。目视检查 [360×640 新布局](screenshots/editor-actions-portrait.png) 和 [640×360 新布局](screenshots/editor-actions-landscape.png)，旧倍率控件缺席，顶栏与操作浮层正常。

验收后恢复两台模拟器的 1080×2400、density 420、自动旋转；硬件键盘显示设置分别恢复为 API 31 的 1、API 36 的 0。

### 当前构建包核验

本轮验证的同一安装包已归档为 `dist/ImageEditApp-debug.apk`，30,379,405 字节，SHA-256 为 `D43DAAFD6813CBB421F51398795DF65AE0E31EE8B832E2F24A920C6D5142E6D8`。`apksigner verify --verbose` 通过（v2 签名有效），`zipalign -c -P 16 4` 通过。

以下保留 2026-10-09 的验收历史，测试结果与 APK 哈希对应当时构建。

## 2026-10-09 · 编辑操作与布局优化（未发布）

本轮验证包为 1.2.0 / versionCode 3 Debug，覆盖六项工具、窄屏底部面板、宽屏侧栏、独立文字输入窗口及查看视口。业务接口、编辑配方和草稿结构沿用原有格式。

### 构建与单元测试

```powershell
$env:JAVA_HOME = 'D:\Program Files\Java\jdk-21.0.11'
.\gradlew.bat assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug --console=plain
git diff --check
```

四项 Gradle 任务成功；43/43 项 JVM 测试通过，0 失败、0 错误。Lint 为 0 错误、12 条警告。`PreviewViewportTest` 共 13 项，新增 8 项覆盖尺寸重映射、手动倍率与查看中心、100%（含小图）、适配、图片边界、缩放范围变化及状态保存恢复。

### API 31 与 API 36 完整回归

| 系统 | 模拟器 | 结果 | 最终完整运行耗时 | 原始日志 |
| --- | --- | --- | ---: | --- |
| Android 12 / API 31 | emulator-5554 / ImageEdit_API31 | 76/76 通过 | 146.963 秒 | [API 31](test-layout-api31.txt) |
| Android 16 / API 36 | emulator-5556 / ImageEdit_API36 | 76/76 通过 | 170.945 秒 | [API 36](test-layout-api36.txt) |

两台基线均为 1080×2400、density 420，使用 Google APIs x86_64、WHPX 和 SwiftShader。最终安装本轮 APK 后执行以下命令；新增 6 项布局测试与原有 70 项草稿、界面、预设、高清预览和导出测试全部通过。

```powershell
adb -s emulator-5554 shell am instrument -w -r com.kang.imageeditapp.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell am instrument -w -r com.kang.imageeditapp.test/androidx.test.runner.AndroidJUnitRunner
```

### 小屏、横屏及真实键盘

| 系统 | 可用测试尺寸 | 布局交互结果 | 耗时 | 原始记录 |
| --- | --- | --- | ---: | --- |
| API 31 | 360×640dp 竖屏 | 6/6 通过 | 48.035 秒 | [设备日志](test-layout-api31-small-portrait.txt)、[几何与键盘坐标](test-layout-api31-small-portrait-geometry.txt) |
| API 31 | 640×360dp 横屏 | 6/6 通过 | 41.851 秒 | [设备日志](test-layout-api31-small-landscape.txt)、[几何与键盘坐标](test-layout-api31-small-landscape-geometry.txt) |
| API 36 | 360×640dp 竖屏 | 6/6 通过 | 54.561 秒 | [设备日志](test-layout-api36-small-portrait.txt)、[几何与键盘坐标](test-layout-api36-small-portrait-geometry.txt) |
| API 36 | 640×360dp 横屏 | 6/6 通过 | 46.843 秒 | [设备日志](test-layout-api36-small-landscape.txt)、[几何与键盘坐标](test-layout-api36-small-landscape-geometry.txt) |

`EditorLayoutUiTest` 验证六项工具展开后的预览尺寸一致、竖屏预览保留至少 56% 工作区、收起/展开保留修改与参数、应用/取消、100% 与中心保持、无效平移后适配仍生效、查看操作不新增撤销，以及直方图展开不改变画布尺寸。还验证最后一项调色参数、HSL 颜色与三种参数、曲线控制点真实拖动、文字字号、裁剪比例和收起后恢复。

文字验收打开真实系统键盘并输入“光影的颜色\n留下这一刻”。确认/取消按钮使用 Compose 的窗口坐标，加上原生 Dialog 窗口的屏幕偏移，与 `TYPE_INPUT_METHOD` 键盘的实际屏幕矩形比较；等待位置稳定后断言两个按钮完全位于键盘上方，并保存未裁切屏幕截图。另一个测试验证取消丢弃输入草稿、确认合并为一步文字撤销。短输入窗口使用横向操作按钮，必要时临时隐藏该输入窗口的状态栏，关闭后恢复。

测试通过 `wm density 160` 和 `wm size 360x640` / `640x360` 切换尺寸；API 31 的系统栏保留了较大的系统间距，应用在其实际剩余空间内也通过验收。结束后恢复两台模拟器的 1080×2400、density 420、自动旋转及原有硬件键盘显示设置。

```powershell
adb -s emulator-5556 shell am instrument -w -r -e class com.kang.imageeditapp.EditorLayoutUiTest com.kang.imageeditapp.test/androidx.test.runner.AndroidJUnitRunner
```

### 布局截图

以下为 API 36 小屏实际截图；曲线截图在滚动至绘图区后取得。截图保留系统栏及键盘，未裁切或重绘。

- [360×640 调色](screenshots/editor-compact-adjust.png)、[曲线](screenshots/editor-compact-curves.png)、[HSL](screenshots/editor-compact-hsl.png)
- [文字面板](screenshots/editor-compact-text.png)、[文字与真实键盘](screenshots/editor-compact-text-ime.png)、[裁剪](screenshots/editor-compact-crop.png)
- [640×360 曲线侧栏](screenshots/editor-landscape-curves.png)、[横屏文字与真实键盘](screenshots/editor-landscape-text-ime.png)
- [API 31 竖屏键盘](screenshots/editor-api31-text-ime.png)、[API 31 横屏键盘](screenshots/editor-api31-landscape-text-ime.png)

### 本轮 Debug 安装包

`dist/ImageEditApp-debug.apk` 为本轮构建与设备测试使用的同一包：31,048,490 字节，SHA-256 为 `F8379A866A6E60AA316D9624FF786784F425935F07061E1A9F576DBD68BDB5D1`。`apksigner verify --verbose` 通过（v2 签名有效），`zipalign -c -P 16 4` 通过。

## v1.2 功能验收（本轮布局优化之前）

以下保留既有功能验收历史，测试数量、耗时及 APK 哈希对应当时构建；本轮 Debug 包以以上记录为准。

验收日期：2026-10-09（Asia/Shanghai）。交付版本：1.2.0 Debug / versionCode 3。

### 当时实现

个人调色预设支持保存、改名、删除、预览、应用和取消；调色参数可跨图片复制粘贴，预设和应用内调色剪贴板跨启动保留。套用仅改变调色、曲线及 HSL，保留目标图片的裁剪、旋转、翻转和文字。

导出新增原尺寸、长边 2048、长边 1080、自定义长边 1–20000 像素，以及 JPEG 质量 50–100。默认原尺寸 JPEG、质量 95；不放大小图，保持裁剪比例。缩小导出直接按目标尺寸区域解码和分块渲染。

### 构建与静态检查

`assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 均成功。Lint 为 **0 错误、12 条警告**，包括固定依赖版本的新版本提示和 Bitmap KTX 风格建议。`git diff --check` 通过。

35 项 JVM 单元测试全部通过：

| 类别 | 数量 | 验证内容 |
| --- | ---: | --- |
| GeometryTest | 4 | 八种 EXIF、裁剪和旋转翻转组合的坐标往返与尺寸 |
| RecipeHistoryTest | 5 | 撤销重做、30 步限制、取消检查点和无变化手势 |
| CurveLutTest | 3 | 默认曲线、独立通道、保形插值和重置 |
| PixelAnalysisTest | 4 | RGB/亮度 256 档、透明像素过滤、高光阴影边界、空输入 |
| PreviewViewportTest | 5 | 适配与原生倍率、缩放中心、平移边界、可见区域和坐标映射 |
| ColorGradeTest | 5 | 调色完整提取、深拷贝、默认值、套用保留构图与文字 |
| ExportOptionsTest | 9 | 默认尺寸质量、等比取整、不放大、横竖图、自定义及非法参数 |

### API 31 与 API 36 设备验收

| 系统 | 设备 | 结果 | 最后完整运行耗时 |
| --- | --- | --- | ---: |
| Android 12 / API 31 | ImageEdit_API31 / emulator-5554 | 70/70 通过 | 98.647 秒 |
| Android 16 / API 36 | ImageEdit_API36 / emulator-5556 | 70/70 通过 | 109.662 秒 |

两台使用 Google APIs x86_64 镜像、WHPX 和 SwiftShader。Debug APK 安装和启动成功。原始日志为 [test-v12-api31.txt](test-v12-api31.txt) 和 [test-v12-api36.txt](test-v12-api36.txt)。

| 测试类 | 数量 | 覆盖 |
| --- | ---: | --- |
| DraftRepositoryTest | 9 | 所有编辑参数和缩略图持久恢复、版本/损坏数据、替换失败、删除、私有路径校验、元数据限制 |
| EditorDraftLifecycleTest | 7 | 新 ViewModel 恢复、应用后立即清理时的最终保存、取消/放弃面板、应用/撤销/重做、关闭恢复删除、导入/保存失败与重试 |
| EditorV11UiTest | 8 | 草稿首页操作、直方图/提示、100% 和双击、两指不产生编辑、缩放后的文字/裁剪坐标、小裁剪原图高清、透明图片单次合成、快速参数与 ROI 更新一致性 |
| PreviewAnalysisIntegrationTest | 7 | 预水印统计与遮罩、裁剪全图统计、32 种 EXIF/旋转/翻转组合、4096px 原图细节、中文水印、窄 ROI、纹理限制 |
| EditorUiTest | 3 | 导入→各工具编辑→JPEG/PNG 保存→系统分享、照片选择取消/失败、返回和工具取消 |
| RenderIntegrationTest | 9 | 调色预览/导出一致、EXIF、透明 PNG/白底 JPEG、分块接缝、超大图保护、中文水印、红色色相边界及保存失败清理 |
| PresetRepositoryTest | 9 | 创建/改名/删除、完整调色与剪贴板恢复、原子写入失败、重复名、Unicode 名称、容量、版本和损坏数据 |
| ColorWorkflowIntegrationTest | 7 | 预设套用保留构图文字、取消与撤销重做、跨图片粘贴、新 ViewModel 恢复、立即关闭时的预设/复制写入 |
| ExportOptionsIntegrationTest | 7 | 实际编码质量差异、EXIF/裁剪/旋转后的目标尺寸、PNG 透明/JPEG 白底、水印位置、默认一致、非法参数、400MP 图片缩小导出 |
| EditorV12UiTest | 4 | 名称校验及改名删除、预设预览取消应用撤销、跨图复制粘贴、导出取消/尺寸/质量/PNG 保存 |

存储故障采用阻塞原子文件临时写入路径的方式注入，验证旧草稿和原图不丢失，当前可见编辑保留，并能恢复保存。该测试验证写入失败路径，未实际耗尽模拟器磁盘。

超大图片测试生成真实的 20000×20000（400MP）一位索引 PNG，直接导出为 1080×1080，验证无需先分配约 1.6 GB 的完整 ARGB Bitmap。该测试与原有原尺寸超限保护、分块接缝测试一同通过。

### 真实进程重启和界面检查

在 API 36 从真实系统 Photo Picker 导入 640×480 测试图，应用非默认曝光，复制调色并保存 Landscape 预设，执行 `am force-stop` 后重新启动。预设库和调色剪贴板与重启前逐字节一致，草稿可继续编辑；预设显示正常，粘贴按钮可用。随后将曝光重置为零并应用，再粘贴重启前的参数，核对落盘草稿恢复到复制值。记录见 [test-v12-process-restart.txt](test-v12-process-restart.txt)。

已目视检查实际 1080×2400 模拟器截图：首页草稿入口、六项工具栏、RGB 直方图、个人预设面板和导出尺寸/质量面板，无重叠或异常裁切。

- [首页](screenshots/home.png)
- [编辑器](screenshots/editor.png)
- [预设](screenshots/presets.png)
- [导出](screenshots/export.png)

### APK 核验与交付

- 包名：`com.kang.imageeditapp`；最低 API 31，target/compile SDK 36。
- 版本：1.2.0 / versionCode 3；Debug 安装包。
- apksigner 验证通过，APK Signature Scheme v2 有效。
- APK 大小：30,865,862 字节。
- SHA-256：`74011491F356CF226CAACDB58D8DA3E5AA70B8F62E97A69DC1892EF4ACC55E1F`。
- 本机文件：`dist/ImageEditApp-debug.apk`、`dist/ImageEditApp-source.zip`；构建安装说明见项目 README。

### 实际边界

保持单图离线、sRGB、8 位 JPEG/PNG 输出。直方图基于最多 1600 像素长边的完整裁剪预览；100% 查看单独读取原图可见区域。查看倍率、溢出提示和导出设置不写入编辑参数，不改变撤销记录。草稿保留最近一份已应用的编辑，撤销历史仅在当前会话保留。

最多保存 30 个个人预设，名称为 1–24 个 Unicode 字符且不能重复；只保留一份私有调色剪贴板。保存预设与复制操作独立于工具取消。预设库和草稿分别保存，卸载或清除应用数据会删除私有内容；尚无预设导入导出或云同步。损坏或未知版本的预设库会保留并报错，目前没有应用内修复入口。

分块导出仍需一个目标尺寸的最终 Bitmap；超出可用内存时提示原因并保留编辑，可改用较小导出尺寸。进程被强制终止前尚未完成的写入只能恢复此前成功保存的状态。API 31/36 已完成模拟器验收，实体设备 GPU 和图库差异尚未实测。
