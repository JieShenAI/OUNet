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

## 本次结果（2026-10-24）

- 冻结构建源码：`b995f1eda3e1ed54b6f86a352b8567cbd0ad7d80`，工作区源码干净；后续提交未更改已构建的应用功能代码。
- 123 项桌面测试、50 项 CLI/发布工具测试通过；包括新增扫描报告缺失、发现威胁及其他安装器摘要不能授权发布的门槛检查。
- 真实 QML 键盘和中文输入、上次账号默认显示、自动复检和按钮稳定性检查通过，无 QML 警告。独立 EXE 在移除开发 Python/Qt 路径后加载全部四页，DPAPI 和 TLS 检查通过。
- 最终扫描：`2026-10-24T09:43:11Z`（北京时间 17:43），Defender 引擎 `1.1.26090.9`，病毒库 `1.461.179.0`。完整程序目录 2016 个文件、Setup 和 ZIP 全部未检出，扫描前后身份一致，实时保护开启。
- Setup：47,609,260 字节，SHA-256 `e6938573ec483c47ec7b4f2998c9a80412db11b296567714c5d7bc893b0704a8`。
- ZIP：67,795,405 字节，SHA-256 `9a410497d0dbdcbacc099ddddb5ee3d3f0e4ecb0b3596c722683ee4e19ead28a`。
- 完整扫描与构建输入记录随 Release 提供；本次未关闭防护、设置排除项、恢复已处置的 0.3.5 安装器或改变认证逻辑。
- [Windows 0.3.6 正式 Release](https://github.com/JieShenAI/OUNet/releases/tag/windows-v0.3.6) 已公开，8 个附件的上传回读及匿名完整下载均通过大小与 SHA-256 校验。

未进行新的真实校园认证、真实安装器覆盖或 Windows 系统矩阵测试；功能回归使用模拟网络与隔离数据。未取得微软对 0.3.5 样本的误报复核结论，0.3.6 的未检出结果不能替代该结论。
