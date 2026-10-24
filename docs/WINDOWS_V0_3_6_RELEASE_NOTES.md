# OUNet Windows 0.3.6

Windows 10 1809+ / Windows 11 x64。

- 保留 0.3.5 的全部界面、上次校园账号信息保留、自动复检、账号编辑及按钮稳定性功能。
- 改用独立的官方 CPython 3.13.9 x64 构建环境，验证 Python Software Foundation 签名和固定的运行时包摘要；不复用开发机的 Conda 运行时。
- 现有依赖版本保持一致，从 PyPI 下载并核对官方 SHA-256，发布构建使用带摘要锁定的依赖清单。
- 构建时分别扫描完整程序目录、Setup 安装器和免安装 ZIP。发布附件增加 `windows-security-scan.json`，记录精确文件摘要、扫描结果、时间和病毒库版本；未通过时停止发布。

安装包：`OUNet-0.3.6-Setup-x64.exe`。免安装包：`OUNet-0.3.6-Windows-x64-portable.zip`，解压后保留完整 `OUNet/` 目录运行 `OUNet.exe`。

升级前从托盘完全退出旧版。AppId、安装位置、账号加密和用户数据目录保持兼容。

0.3.5 的 Setup 曾被 Defender 检测为 `Trojan:Win32/Wacatac.H!ml`。0.3.6 的扫描范围与结果以随包报告和[验证记录](https://github.com/JieShenAI/OUNet/blob/main/docs/WINDOWS_V0_3_6_VERIFICATION.md)为准。单次扫描不保证其他引擎或未来病毒库一定不报，当前版本也未进行 Windows 代码签名；SmartScreen 的应用信誉提示仍可能出现。遇到明确病毒检测请保留拦截并反馈，不应关闭防护或添加排除项。
