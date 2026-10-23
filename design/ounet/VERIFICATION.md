# OUNet 品牌更新验证 · 2026-10-23

本次在现有未提交开发内容之上，仅接入 OUNet 名称与品牌资源，没有重置已有修改。

- Android：137 项单元及 Robolectric 界面测试通过；最后一次 `lintDebug` 为 `No issues found.`；正式签名构建成功。
- 使用 Android SDK aapt 检查最终 APK：显示名称 `OUNet`，applicationId `cn.jieshen.oucautologin`，versionName `1.3.3`，versionCode `7`。
- 原正式证书核验通过；OUNet 主 APK 与旧文件名兼容 APK SHA-256 完全相同，更新元数据指向兼容文件。
- Windows：54 项桌面测试与 40 项 CLI 回归通过，四页真实 QML 离屏加载和交互检查通过；已检查实际 Android、Windows 界面截图。
- Windows PyInstaller 打包成功，独立 EXE 在清除开发 Python/Qt 路径的环境中运行成功，四页截图、DPAPI 和 TLS 检查通过；Inno Setup 安装器编译成功。
- 横版 Logo 2172×724；手机和桌面图标母版 1254×1254；桌面与 Logo 的透明通道真实存在。ICO 包含 16、20、24、32、40、48、64、128、256 九个尺寸。
- `verify_assets.py` 验证了 PNG 透明角、ICO 目录与 PNG 数据、Android 兼容包及元数据散列、Windows 安装器校验文件；结果见 `verification.json`。

本次没有安装到真实设备或当前 Windows 用户环境，没有验证真实覆盖安装、自启动迁移或校园网络认证，也没有推送 Git 提交或远程发布 Release。

素材以原始内置 image_gen PNG 为准。透明白色前景的两次尝试边缘质量不足，未用于交付；Android 使用清晰满版母版与系统安全边距，另附简化的原生单色图标。
