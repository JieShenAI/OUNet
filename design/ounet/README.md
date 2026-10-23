# OUNet 品牌素材

`auto_login_ouc` 的 Android 和 Windows 对外名称统一为 **OUNet**。
开口 O 与海浪合成连接标识，沿用项目海洋蓝 `#004098` 和青色 `#60CEFF`。
图标以简洁轮廓适应手机启动器、桌面快捷方式和任务栏。

## 素材

| 文件 | 用途 |
| --- | --- |
| `ounet-logo.png` | 透明底横版 Logo，供两端关于页、发布页及文档使用 |
| `ounet-mobile.png` | 满版方形手机图标母版，由 Android 启动器施加圆形或圆角遮罩 |
| `ounet-desktop.png` | 透明底圆角桌面图标母版 |
| `../../desktop/assets/app.ico` | Windows 多尺寸 ICO，16/20/24/32/40/48/64/128/256 px |
| `../../desktop/assets/tray-*.ico` | 带状态点的多尺寸托盘图标 |
| `android-png/` | 手机图标 mdpi 至 xxxhdpi PNG 导出 |
| `../../android/app/src/main/res/mipmap-anydpi/ic_launcher.xml` | Android 自适应图标；系统施加形状遮罩 |

三张母版使用内置 **image_gen** 生成，桌面和 Logo 以手机母版为品牌参考。
完整生成提示词见 [PROMPTS.md](PROMPTS.md)。保留 PNG 原始透明通道；没有伪造 SVG 矢量文件。
旧版 SVG 和历史设计稿留存供追溯，应用运行及构建使用本目录的新母版。
Android 最低版本为 28，直接使用自适应资源。前景使用原始清晰母版并内缩 12dp，将标识放进系统安全区；Android 13 单色主题图标另用简化矢量轮廓。

## 导出

在仓库根目录使用已安装 PySide6 的环境：

```powershell
.\desktop\.venv\Scripts\python.exe design/ounet/export_android.py
.\desktop\.venv\Scripts\python.exe desktop/tools/prepare_assets.py
```

## 发布与兼容

- Android 应用显示名为 `OUNet`，调试版为 `OUNet·测试`，主安装包为 `OUNet-<版本>-release.apk`。
- 保持 Android applicationId、正式签名及既有账号存储不变。发布脚本同时生成字节一致的 `ouc-autologin-<版本>-release.apk` 和校验文件，更新元数据继续指向此兼容文件；发布时必须一起上传，保证旧版内置更新校验仍然有效。
- Windows 安装包为 `OUNet-<版本>-Setup-x64.exe`，程序为 `OUNet.exe`，界面、快捷方式、托盘及文件产品信息均显示 OUNet。
- 保持 Windows 安装 AppId、安装目录、数据目录 `%LOCALAPPDATA%\OUCNetworkAssistant`、单实例锁及自启动注册值名称。安装器只迁移原安装路径下的旧自启动命令，保持已有自启动选择。
- 本次只修改本地源码和构建素材，不自动发布远程 Release，不安装或修改当前运行的应用。
