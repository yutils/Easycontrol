# 易控 EasyControl

> 安卓端控制安卓端的远程投屏工具，基于 Scrcpy 协议魔改实现。

本项目 Fork 自 [mingzhixian/Easycontrol](https://github.com/mingzhixian/Easycontrol)，在此对原作者表示衷心感谢。原项目已多年未更新，本项目在其基础上进行了大量现代化改造与 Bug 修复，并将持续维护。

**永久免费 · 开源 · 持续维护**

| ![Img1](pic/screenshot/1.jpg) | ![Img1](pic/screenshot/3.jpg) | ![Img1](pic/screenshot/5.jpg) |
| -- | -- | -- |


## 与原版的差异
### 一、概要
- 移除捐赠 / 激活逻辑，永久免费使用
- 兼容被控端为安卓15以上系统
- Scrcpy升级到最新版
- 新增新增远程截图
- 新增虚拟鼠标
- 新增远程音量调节功能 （可配置断开后设备静音）
- 新增设备状态面板

### 二、UI 全面现代化
- 引入appcompat主题（APK体积变大）
- 全新设计语言：绿色主调色彩系统，支持深色模式
- 精细化尺寸系统：统一字号、间距、圆角、阴影层次
- 卡片式布局：圆角卡片 + 细描边，视觉更清爽
- Ripple 涟漪反馈：所有可点击元素带触摸反馈
- 重做全部核心页面：首页、设备详情、设置、投屏、悬浮窗、加载弹窗、对话框
- 投屏界面统一半透明深色浮层 + 白色图标，关闭键红色高亮
- 开关组件适配主题色（开启绿色 / 关闭灰色，不再与背景融为一体）
- 横竖屏自适应布局优化

### 三、Android 15 适配
- 适配 `SurfaceControl` API 变更：`createDisplay`/`destroyDisplay` 迁移至 `DisplayControl.createVirtualDisplay`/`destroyVirtualDisplay`
- 适配 `SurfaceControl` 静态方法移除：`setDisplaySurface`/`setDisplayProjection`/`setDisplayLayerStack` 改用 `SurfaceControl.Transaction` 对象（仅 Android 15+ 启用，Android 14 及以下保留静态方法，兼容性最佳）
- 适配 `AudioRecord` 的 `native_setup` 签名变更（Android 14 QPR3 / 15）
- 适配 `WindowManager` 的 `caller` 参数变更

### 四、Scrcpy 协议同步至 v4.x
- 低延迟编码参数：`KEY_PRIORITY`、`KEY_LATENCY`
- 非侵入式防息屏：使用 `PowerManager.userActivity` 替代修改 `screen_off_timeout`
- 视频编码参数优化

### 五、Bug 修复（40+ 项）
- 崩溃与资源泄漏
- 逻辑错误
- 连接稳定性

### 六、功能调整
- 启动时主动申请所需权限（悬浮窗、文件读取、前台服务、通知）
- 设备名称默认按序号命名（设备1、设备2、设备3）
- 首页设备列表同时显示设备名称与 IP 地址
- 加载弹窗重构为正方形布局，显示"加载中"

## 七、功能特色
- 使用简单
- 支持音频传输
- 多设备连接
- 支持有线连接
- 多设备剪切板同步
- 多设备共享主控端物理键盘
- 启动迅速，低延迟
- 支持分辨率自适应
- 良好的旋转支持
- 支持小窗显示与全屏显示
- Android 14 / 15 完整适配

## 八、构建
- gradle构建环境升级的最新版
- 升级到java17
本项目使用 Android Studio + Gradle 构建。如需自行编译：

```bash
# Windows
.\easycontrol\gradlew.bat assembleDebug -p easycontrol

# Linux / macOS
./easycontrol/gradlew assembleDebug -p easycontrol
```

编译产物：`easycontrol/app/build/outputs/apk/debug/app-debug.apk`

## 相关项目

- **原项目**：[mingzhixian/Easycontrol](https://github.com/mingzhixian/Easycontrol) — 致敬原作者
- **Scrcpy**：[Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) — 本项目基于的投屏协议
- **ADB 协议文档**：[cstyan/adbDocumentation](https://github.com/cstyan/adbDocumentation)

## 开源协议

遵循原项目开源协议，保留原作者版权声明。

## 反馈

请在 GitHub 提出 Issue：[https://github.com/yutils/Easycontrol/issues](https://github.com/yutils/Easycontrol/issues)

## 项目地址

**GitHub**：[https://github.com/yutils/Easycontrol](https://github.com/yutils/Easycontrol)
