# OUNet

中国海洋大学校园网连接助手，包含 Android 应用、Windows 桌面应用与独立 Python CLI。
个人开发项目，非中国海洋大学官方软件。

![OUNet](design/ounet/ounet-logo.png)

| 平台 | 当前源码版本 | 说明 |
| --- | --- | --- |
| Android 9+ | 1.3.4 | [安装与使用](docs/ANDROID.md) · [开发与构建](android/README.md) |
| Windows 10/11 x64 | 0.3.0 测试版 | [安装与使用](docs/WINDOWS.md) · [开发与构建](desktop/README.md) |
| Python CLI / Windows | 独立脚本 | [配置、启动与故障排查](docs/CLI.md) |

Android 与 Windows 提供外网检测、按需校园网认证、多账号、设备信息、失败保护及诊断。
各平台的调度与保护策略以对应文档为准。

## 获取与运行

源码：[JieShenAI/OUNet](https://github.com/JieShenAI/OUNet)。
安装包通过 [GitHub Releases](https://github.com/JieShenAI/OUNet/releases) 分发；迁移源码不会自动创建安装包或 Release。

CLI 需要 Python 3.10+，在仓库根目录运行：

```powershell
python -m pip install -r requirements.txt
Copy-Item .env.example .env
# 编辑 .env，填写自己的账号、密码及 Wi-Fi 配置
python auto_login.py
```

Windows 桌面版的源码启动、演示模式和独立环境见 [desktop/README.md](desktop/README.md)。
Android 使用 JDK 17+、Android SDK 35 与项目附带的 Gradle Wrapper，见 [android/README.md](android/README.md)。

## 代码结构

```text
android/             原生 Java Android 应用、资源与单元测试
desktop/             Python / PySide6 / QML Windows 桌面应用与打包工具
auto_login.py        独立 CLI 与共享认证实现
tests/               CLI 离线测试
docs/                使用指南、协议说明、规划与验证记录
design/              品牌素材、界面原型与导出脚本
prompt/              不含凭据的历史需求记录
archive/ouc_auto_net/ 迁移保留的旧 CLI 快照
.github/             CI、问题模板与 Pull Request 模板
```

历史来源、目录映射、本地文件与更新源变化见 [迁移记录](docs/MIGRATION.md)。

## 开发与测试

```powershell
python -m unittest discover -s tests -v
# 安装桌面依赖后，在 Windows 上执行：
python -m unittest discover -s desktop/tests -v
# Android（也可在 android 目录使用 gradlew.bat）：
cd android
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

也可使用 `uv sync` 安装 CLI 依赖，或 `uv sync --group desktop` 安装桌面开发依赖。
CI 自动运行 CLI、Windows 桌面离线测试及 Android 单元测试/Lint。签名安装包通过手动构建流程生成。
测试使用模拟响应与临时数据；真实校园认证、后台保活、休眠恢复和覆盖安装仍需实机验证。

## 参与贡献与许可

提交前阅读 [贡献指南](CONTRIBUTING.md)，问题请提交到 [Issues](https://github.com/JieShenAI/OUNet/issues)。
安全问题和凭据处理见 [SECURITY.md](SECURITY.md)。

项目自有代码与原创素材采用 [MIT](LICENSE) 许可证。
第三方库、Gradle Wrapper、学校标识和摄影等保留原有许可与权利，见 [第三方说明](THIRD_PARTY_NOTICES.md)。
