# 修改记录

记录已完成的功能、修复和行为变更，按版本从新到旧排列。每次代码、配置、文档或测试修改完成后，在交付或提交前补充日期、具体内容及必要的验证结果；尚未发布的内容写入“未发布”，发布时归入对应版本。保留既有历史，不将计划中的功能写成已完成。

## 未发布

### 2026-10-10 · 工具面板展开时隐藏标题栏

- 标题栏与编辑工作区（`app/src/main/java/com/kang/imageeditapp/ui/ImageEditorApp.kt`）：展开调色、曲线、HSL、预设、文字或裁剪面板时隐藏顶部 56dp 标题栏，释放全部标题栏高度给工作区；收起、应用或取消后恢复标题栏及更换图片、导出入口。面板收起状态提升到界面根部，选择另一工具立即展开，工作区和查看状态持续保留，收起不提交编辑。
- 宽屏标题栏隐藏时，直方图和溢出提示入口回到图片浮层；恢复标题栏后入口回到顶栏，保持唯一且可达。尺寸徽标避开展开的直方图及溢出说明，撤销、重做、按住对比仍位于预览右下角。
- 界面验收（`EditorLayoutUiTest`、`EditorUiTest`、`EditorV11UiTest`、`EditorV12UiTest`）：现有六项布局测试增加标题栏显隐、实际回收 56dp、收起/展开保留参数和 100% 查看中心、应用/取消恢复及横屏分析入口迁移检查；保存收起截图，核对导出、更换图片和返回入口恢复。README 与 `docs/VALIDATION.md` 同步新行为、原始日志和截图，保留之前的验证历史。
- 验证：`assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` 同次构建成功，43/43 项 JVM 测试通过，Lint 0 错误、12 条警告。API 31、API 36 各 21/21 项相关界面回归通过；两台模拟器的 360×640dp 与 640×360dp 各 6/6 项布局回归通过，包含真实键盘、中文多行输入及曲线拖点；目视检查展开、收起和小屏截图。验证后恢复两台模拟器的尺寸、密度、旋转及键盘设置。
- 交付：更新 `dist/ImageEditApp-debug.apk`，30,379,405 字节，SHA-256 为 `2E7D23637DDEA1F52F533374B81C5AF5BEA54982B0F663D27271A4330EF41740`；`apksigner verify --verbose` 通过（v2 签名有效），`zipalign -c -P 16 4` 通过。文档内容、链接核对和 `git diff --check` 通过。详见 [验证说明](docs/VALIDATION.md)。

### 2026-10-10 · 图片操作按钮移到预览右下角

- 图片操作与顶栏（`app/src/main/java/com/kang/imageeditapp/ui/ImageEditorApp.kt`）：移除图片右下角的倍率文字、“适配”和“100%”按钮，将撤销、重做、按住查看原图移到预览右下角浮层，三个按钮均为 48dp，顶栏相应简化。双指缩放、平移和双击切换适配/100% 保留。
- 原图对比在按下时消费事件，松手、取消或手势终止时通过 `try/finally` 恢复编辑效果；按钮不触发图片画布手势。查看验收（`PreviewViewport.kt` 与图片画布）：新增 `PreviewPixelScaleKey`、`PreviewViewportModeKey` 语义属性，用于读取实际倍率和查看模式，不显示倍率控件。
- 回归测试（`EditorLayoutUiTest`、`EditorUiTest`、`EditorV11UiTest`、`PreviewGestureTestHelpers`）：原倍率按钮操作改为真实双击手势和非可见倍率读取，手势避开操作浮层；保留高清区域、双指与文字/裁剪坐标检查，扩展按钮位置、撤销重做及按住对比的像素变化和松手恢复检查。
- 验证：`assembleDebug testDebugUnitTest`、`assembleDebugAndroidTest lintDebug` 成功，43/43 项 JVM 测试通过，Lint 0 错误、12 条警告。API 31、API 36 各 21/21 项相关界面回归通过；两台模拟器在 360×640dp 和 640×360dp 各通过 6/6 项布局回归，保存新按钮布局截图及原始日志。Debug APK 已归档到 `dist/ImageEditApp-debug.apk`，30,379,405 字节，SHA-256 为 `D43DAAFD6813CBB421F51398795DF65AE0E31EE8B832E2F24A920C6D5142E6D8`；`apksigner verify --verbose` 通过（v2 签名有效），`zipalign -c -P 16 4` 通过。文档内容、链接核对及 `git diff --check` 通过。详见 [验证说明](docs/VALIDATION.md)。

### 2026-10-09 · 编辑操作与预览布局优化

- 编辑工作区与工具面板（`app/src/main/java/com/kang/imageeditapp/ui/ImageEditorApp.kt`）：使用 56dp 顶栏、64dp 底部工具栏，窄屏参数面板限制为最多 240dp 且不超过可用编辑区高度的 44%，按像素向下取整保证至少 56% 预览空间，图片画布留白缩至 12dp；宽度至少 600dp 时改为左侧工具栏、中央图片和右侧 280dp 参数面板。窄屏可收起到 48dp 标题栏，侧栏可收起到 56dp。直方图改为可关闭的浮动卡片，图片信息和提示改为浮层；横屏入口移到顶栏，较矮预览区隐藏辅助文字。
- 支持手动收起、展开工具参数面板及再次点击当前工具切换面板显示；收起保留未应用的编辑和参数选择，不触发应用或取消。调色与 HSL 改为参数选择配合单条滑杆，保留各项调整、单项重置、全部调色重置和手势级撤销。曲线绘图区为 144dp，预设与裁剪内容可滚动。文字改为独立输入窗口，保留多行及 240 字符限制；确认形成一步撤销，取消保留原文字，紧凑输入布局保证确认/取消在真实键盘上方。
- 查看状态（`app/src/main/java/com/kang/imageeditapp/ui/PreviewViewport.kt`）：将适配与手动查看状态独立保存，普通工具切换、面板展开或收起及布局尺寸变化保持 100% 或手动查看的实际像素倍率与图片中心，平移按图片边界约束；适配模式随可用区域调整。普通编辑和裁剪分别保存视口，支持 Activity 重建恢复，保留原图区域高清解码及双指只改变查看状态的行为。
- 回归测试与说明（`PreviewViewportTest`、`EditorLayoutUiTest`、`EditorUiTest`、`EditorV11UiTest`、`EditorV12UiTest`、README 及 `docs/VALIDATION.md`）：新增 8 项视口单元测试及 6 项布局交互设备测试，覆盖倍率重映射、适配与手动状态、视口恢复、面板收起与工具切换、单参数滑杆及文字确认/取消；更新原有工具操作测试适配新入口。
- 验证：`assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` 成功，43 项 JVM 测试全部通过，其中 `PreviewViewportTest` 为 13 项；Lint 0 错误、12 条警告。API 31、API 36 最终全套设备测试各 76/76 通过，耗时分别为 146.963 秒、170.945 秒；两台模拟器的 360×640dp 竖屏和 640×360dp 横屏各 6/6 布局测试通过，保存布局、真实键盘截图及原生窗口/按钮/键盘坐标。Debug APK 的 `apksigner verify --verbose` 与 `zipalign -c -P 16 4` 通过。README、验证说明、CHANGELOG 的内容和链接核对及 `git diff --check` 通过。详见 [验证说明](docs/VALIDATION.md)。

### 2026-10-09

- Release 签名与 R8 打包（`app/build.gradle.kts`、`app/proguard-rules.pro`、`.gitignore`、`keystore.properties.example`、README 及 `docs/RELEASE_VALIDATION.md`）：新建本机 RSA 4096 位 Release 密钥，随机密码写入被忽略的本机配置；Release 启用 R8 full mode 的代码压缩、优化、混淆与资源压缩，使用优化版默认规则和依赖自带保留规则。版本保持 1.2.0 / versionCode 3。
- 交付签名 APK `dist/ImageEditApp-release.apk`（2,361,620 字节），归档对应 R8 映射和核验信息。密钥、密码及交付文件不进入 Git；补充重新构建、密钥备份、映射归档及异签名安装说明。
- Release 验证：`assembleRelease testReleaseUnitTest lintRelease` 成功，35 项 JVM 测试全部通过，Lint 0 错误、12 条警告；`apksigner verify --verbose --print-certs` 和 `zipalign -c -P 16 4` 通过，签名证书与新密钥一致，Manifest 包名、版本、SDK 和非 debuggable 状态核验通过。临时缺少签名配置时，`assembleDebug --dry-run` 可正常配置，`assembleRelease` 明确拒绝生成未签名包，恢复配置后构建成功。详见 [Release 验证说明](docs/RELEASE_VALIDATION.md)。
- 最终签名、混淆 APK 在独立 API 36 模拟器上通过安装冷启动、真实 PhotoPicker 导入、曝光应用、JPEG/PNG 导出，以及强制停止后草稿恢复检查；导出文件解码、像素变化和重启前后完整 JSON 核对通过，crash buffer 为空。测试证据保存在本机 `dist/release-validation/`，独立实例已关闭，既有设备数据未修改。
- 新增本文件，补齐 v1.0、v1.1、v1.2 的修改与验证记录。
- 在 README 添加修改记录入口；新增项目维护约定 [AGENTS.md](AGENTS.md)，要求后续每次代码、配置、文档或测试修改后同步记录实际改动和验证结果。
- 将持续维护修改记录的要求保存到本机长期记忆。
- 验证：核对 Git 提交日期、历史验收说明、当前功能说明及文件链接，执行 `git diff --check`；本次仅修改文档，未重新构建 App 或运行应用测试。

## v1.2.0 · 2026-10-09

- 新增个人调色预设：保存、改名、删除、预览、应用和取消；最多保存 30 个，名称为 1–24 个 Unicode 字符且不能重复。
- 支持跨图片复制、粘贴调色参数；个人预设和应用内调色剪贴板通过私有原子存储跨启动保留。套用只改变调色、曲线与 HSL，保留目标图的裁剪、旋转、翻转和文字，并支持撤销。
- 新增导出尺寸选择：原尺寸、长边 2048、长边 1080、自定义长边 1–20000 像素；保持比例且不放大小图。
- 支持 JPEG 质量 50–100，默认 95；PNG 保留透明度。缩小导出直接按目标尺寸区域解码和分块渲染，降低大图内存占用。
- 验证：构建、35 项 JVM 测试及 lint 通过；API 31、API 36 模拟器各 70 项测试通过，lint 为 0 错误、12 条警告。完成实际进程重启后的预设、调色剪贴板和草稿恢复检查，以及 400MP 图片缩小导出验证。详见 [验证结果](docs/VALIDATION.md)。

## v1.1.0 · 2026-10-09

- 新增最近一张图片的自动草稿保存与恢复，首页支持继续编辑、删除草稿；持久保存原图、带版本号的编辑参数和缩略图，采用原子写入。
- 应用、撤销、重做后自动保存；新图成功导入后替换草稿，导入取消或失败保留旧内容。撤销历史仅在当前会话保留。
- 支持双指缩放、平移，以及双击切换适配和 100% 查看；原图可见区域通过区域解码和共用 shader 高清渲染，同步文字与裁剪的坐标映射。
- 新增可折叠的 RGB／亮度 256 档直方图，统计完整裁剪预览，忽略完全透明像素和水印；新增红色高光、蓝色阴影溢出提示，仅用于预览。
- 验证：构建、21 项 JVM 测试及 lint 通过；API 31、API 36 模拟器各 43 项测试通过，lint 为 0 错误、12 条警告。完成真实照片选择器导入、进程强制停止与重启后的草稿恢复检查。

## v1.0.0 · 2026-10-09

- 建立 Android 12 及以上的原生单图离线编辑 App，采用 Kotlin、Jetpack Compose、Material 3、ViewModel、StateFlow 和中文深色界面。
- 提供曝光、亮度、对比度、饱和度、色温、色调、阴影、高光调整与单项重置；支持 RGB 总曲线、独立 R/G/B 曲线及八组颜色的 HSL 调整。
- 提供单段文字水印，支持中文、多行、颜色、字号与拖动位置；裁剪支持自由、原比例、1:1、4:3、16:9，以及 90° 旋转、水平／垂直翻转。
- 提供工具应用／取消、原图对比和最多 30 步的手势级撤销／重做；通过系统照片选择器导入，缺少选择器时回退到系统文件选择器。
- 预览与导出共用 OpenGL ES 2.0 shader、曲线 LUT 和水印布局；处理八种 EXIF 方向并分块渲染。支持原尺寸 JPEG／PNG 保存到 `Pictures/ImageEditApp` 和系统分享，JPEG 透明区域合成白底，失败时清理未完成文件。
- 验证：构建、12 项 JVM 测试及 lint 通过；API 31、API 36 模拟器各 12 项测试通过，lint 为 0 错误、9 条警告。覆盖完整编辑、导出和分享流程，以及 EXIF、透明度、分块接缝、水印位置和失败清理。
