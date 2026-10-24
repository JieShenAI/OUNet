# Windows 0.3.6 构建与安全验证

## 范围

0.3.5 的安装器触发 Defender `Trojan:Win32/Wacatac.H!ml`。本次保留业务功能，通过全新官方运行时、官方依赖摘要锁和安装前扫描建立可追溯的发布输入及验证记录。未将该告警擅自认定为微软已确认的误报。

基线：2026-10-24 更新安全情报后，Defender 引擎 `1.1.26090.9` / 病毒库 `1.461.179.0` 扫描本地 0.3.5 完整程序目录及免安装 ZIP 均未检出；原 Setup 已被处置，未恢复或运行。

## 构建输入

- 官方 Python NuGet：`https://api.nuget.org/v3-flatcontainer/python/3.13.9/python.3.13.9.nupkg`。
- 包 SHA-256：`ea7541c98f77b6f344460b88db6e677573069979bcbf0ba18747cc2c125aba25`；运行时 `python.exe` 的 PSF Authenticode 签名验证通过。
- 依赖与 0.3.5 保持相同版本，wheel 摘要由 PyPI 对应版本 JSON 核对，固定于 `desktop/requirements-release.lock.txt`。
- Inno Setup 编译器数字签名必须有效。PyInstaller 保持 onedir、`upx=False`，执行 clean build；不添加混淆、隐藏执行或防护排除。

## 发布门槛

扫描使用 Defender 官方 `MpCmdRun -Scan -ScanType 3 -DisableRemediation`：该扫描模式忽略路径排除并检查压缩包，不修改实时保护设置，不自动恢复或允许威胁。文件摘要在扫描前后须一致；任何发现、错误或文件变化均阻断打包发布。

构建仍执行既有桌面、CLI、QML 和独立 EXE 检查。精确扫描时间、引擎、病毒库版本、安装器与 ZIP 摘要记录在发布附件 `windows-security-scan.json`。

当前阶段：等待本次构建、回归和最终扫描。结果完成后在此记录；未通过不得宣称“无病毒”或发布到正式更新渠道。

未进行新的真实校园认证、真实安装器覆盖或 Windows 系统矩阵测试；功能回归使用模拟网络与隔离数据。未取得微软对 0.3.5 样本的误报复核结论，0.3.6 的未检出结果不能替代该结论。
