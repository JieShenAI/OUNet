# 更新记录

Android 与 Windows 分别使用 `android-vX.Y.Z` 和 `windows-vX.Y.Z` 标签。

## Unreleased

- 将校园网相关源码从 `JieShenAI/tools` 拆分为独立 OUNet 仓库。
- 统一 MIT 许可证，补齐贡献指南、凭据说明、问题模板、PR 模板和持续集成。
- 调整独立仓库的文档、构建路径与 Android / Windows 更新地址。
- 保留 `archive/ouc_auto_net/` 旧 CLI 快照。

## 已有源码版本

- Android 1.3.4：[发布说明](docs/ANDROID_V1_3_4_RELEASE_NOTES.md)。
- Windows 0.3.0 测试版：[实施与验证](docs/WINDOWS_V0_3_VERIFICATION.md)。

本次目录迁移未创建二进制 Release，也未改变既有 Android 包名、签名或 Windows 安装标识。
