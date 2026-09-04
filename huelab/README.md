# HueLab

HueLab 是用于 PattleCNN 四色训练数据的 Compose Multiplatform 标注客户端，当前支持 Android 13+ 和 Desktop（JVM）。

## 功能

- 用户注册、登录、刷新登录状态和安全退出
- 从 HueLab.Server 领取图片并在本地执行四簇 KMeans
- 从原图点击/拖动取色，或通过 HSV 滑条与 HEX 输入调整颜色
- 使用四个颜色实时驱动 Android RuntimeShader / Desktop Skia Shader
- 左滑下一张、右滑返回本次会话上一张，离开图片时异步上传
- 会话内保留失败的标注并支持重试
- 已下载图片持久化到本地缓存（上限 256 MB），再次查看与历史编辑优先离线读取
- 分页查看个人历史、重新调色并显式保存修改
- 紧凑窗口纵向布局和宽屏三栏布局

## 运行

在 IntelliJ IDEA 中导入 `huelab` 目录后，运行配置列表会提供 **HueLab Desktop**，直接运行即可启动桌面客户端。该配置等价于执行 `:desktopApp:run`。

项目默认连接：

```text
https://huelab.raspberrykan.dev:16386
```

Desktop 可通过环境变量或 JVM 属性覆盖 API 地址：

```powershell
$env:HUELAB_API_BASE_URL = "https://example.test"
.\gradlew.bat :desktopApp:run
```

Android 可通过 Gradle 属性覆盖：

```powershell
.\gradlew.bat :androidApp:assembleDebug -PhuelabApiBaseUrl=https://example.test
```

常用命令：

```powershell
.\gradlew.bat :shared:jvmTest
.\gradlew.bat :desktopApp:run
.\gradlew.bat :androidApp:assembleDebug
```

Debug APK 位于 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`。

## 自定义 Shader

标注页可加载 UTF-8 编码、最大 256 KB 的 `.sksl`、`.agsl` 或 `.txt` 文件。为同时兼容 Android 和 Desktop，源码必须提供以下入口与 uniforms：

```c
uniform float2 resolution;
uniform float time;
uniform half4 color0;
uniform half4 color1;
uniform half4 color2;
uniform half4 color3;

half4 main(float2 fragCoord) {
    // 返回当前像素颜色
}
```

编译失败时应用会继续使用上一个有效 Shader，并显示编译错误。

## 安全与数据约定

- 只持久化 refresh token，不保存用户名密码。
- Android 使用 Android Keystore；Desktop 使用 Windows Credential Manager、macOS Keychain 或 Linux Secret Service。
- 客户端向服务端提交恰好四个大写 `#RRGGBB` 色值。
- 历史修改复用 `POST /api/images/{imageId}/colors`。
