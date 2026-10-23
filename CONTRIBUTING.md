# 贡献指南

欢迎通过 Issue 反馈问题，或通过 Pull Request 提交改进。提交前先说明问题、预期行为与复现方法；较大改动请先开 Issue 讨论。

## 本地开发

1. Fork 并克隆仓库，创建工作分支，例如 `codex/fix-description`。
2. CLI：Python 3.10+，`python -m pip install -r requirements.txt`。
3. Windows：在 Windows 上按 [桌面开发指南](desktop/README.md) 创建独立虚拟环境。
4. Android：按 [Android 指南](android/README.md) 配置 JDK 17+ 和 SDK 35。

## 提交要求

- 使用清晰的提交信息，例如 `fix(android): handle captive portal redirects`。
- 修改行为时同步更新对应文档，并运行受影响平台的现有测试。
- CLI：`python -m unittest discover -s tests -v`。
- Windows：`python -m unittest discover -s desktop/tests -v`，必要时运行 QML smoke/UI 检查。
- Android：在 `android/` 执行 `./gradlew :app:testDebugUnitTest :app:lintDebug`（Windows 使用 `gradlew.bat`）。
- 测试使用模拟响应和临时数据，避免向真实校园网自动提交认证。
- 不提交账号、密码、`.env`、签名材料、原始认证响应、个人设备信息、缓存或构建产物。
- PR 说明具体变化、验证结果与尚未验证的实机场景。遵循 [第三方许可说明](THIRD_PARTY_NOTICES.md)。

提交的自有代码按本项目 MIT 许可证提供；第三方内容须注明来源与许可。
