# 校园网自动登录工具说明

**最新 Windows 版本（2026-10-23）**：0.3.0 本地测试版已按 [v0.3 规划](WINDOWS_DESKTOP_V0_3_DESIGN_PLAN.md)更新真实 QML 和程序逻辑，见[安装使用](WINDOWS.md)与[实施验证](WINDOWS_V0_3_VERIFICATION.md)。[四页设计稿](../design/windows/v0.3/README.md)保留供对照。

Android 1.3.4 的实际功能、安装和品牌后台设置见 [Android APK 指南](ANDROID.md)，本次外网优先、在线停止自动请求XHA的更新见 [1.3.4实施验证](ANDROID_V1_3_4_FIX_VERIFICATION.md)，历史检查见 [Android 验证记录](ANDROID_VERIFICATION.md)。

本版对应的[认证、主动连接、诊断与权限提示规划](ANDROID_V1_3_2_LOGIC_PLAN.md)保留设计决策和验收清单。

本工具面向 Windows。它监测外网连接；断网时尝试连接配置的校园 Wi-Fi，读取中国海洋大学校园网认证页当前配置，再提交 Portal 登录并检查网络是否恢复。

本目录文档只介绍校园网子项目。代码和入口脚本位于本目录的父目录；自动登录详细背景见[CLI 使用说明](CLI.md)。

Windows 桌面安装软件的规划资料见 [Windows 产品规划](WINDOWS_PRODUCT_PLAN.md)、[可交互原型](../design/windows/index.html)和[设计交付说明](../design/windows/README.md)。原型用于审阅，不执行登录；实际软件见下方链接。

最新的 [Windows desktop v0.2 更新设计与实施规划](WINDOWS_DESKTOP_V0_2_PLAN.md)及[新版交互原型](../design/windows/desktop-v0.2.html)覆盖启动检测、会话详情、多账号、秒级间隔与请求暂停等更新，已实现到 0.2.0 桌面测试版。门户只读字段的核验见 [协议记录](WINDOWS_PORTAL_PROTOCOL.md)。

已实现的 Windows 0.2.0 桌面测试版见 [安装与使用说明](WINDOWS.md)和[源码、构建与测试](../desktop/README.md)，可导入原脚本配置并保留旧暂停待处理状态。下文独立 CLI 仍使用原规则。

已执行与待进行的检查见 [Windows 验证记录](WINDOWS_VERIFICATION.md)。

## 要求

- Windows 10/11，Python 3.10 或更新版本。
- 能访问校园网认证服务器的无线连接；默认 SSID 和已保存的 Wi-Fi 配置名称为 `HUAWEI-Jie_5G`。
- `requests` Python 包。
- Windows 中已保存目标无线网络的连接配置。首次使用前，请手动连接一次该网络。

可在子项目目录执行：

```powershell
python -m pip install -r requirements.txt
```

仓库根目录使用 uv 项目环境时，可在仓库根目录执行 `uv sync --no-install-project`。

## 设置账号和网络

在此目录创建 `.env`。可以复制 `.env.example` 作为模板，然后填入账号密码。文件格式为 UTF-8 `KEY=VALUE`，不要把密码写进引号、Notebook、批处理参数或仓库跟踪的文件中。以 `0` 开头的密码会按文本完整保留。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `OUC_USERNAME` | 必填 | 校园网账号 |
| `OUC_PASSWORD` | 必填 | 校园网登录密码 |
| `OUC_SSID` | `HUAWEI-Jie_5G` | 脚本必须确认连接上的无线网络名称 |
| `OUC_WIFI_PROFILE` | 与 SSID 相同 | Windows 保存的 WLAN 配置名称 |
| `OUC_INTERVAL` | `60` | 长期模式的检查间隔（秒），不得小于 10 |
| `OUC_TIMEOUT` | `8` | HTTP 请求超时时间（秒），范围为 `(0, 30]` |
| `OUC_FAILURE_WINDOW` | `600` | 连续失败的滑动统计窗口（秒），必须大于 0；默认 10 分钟 |

同名的进程环境变量优先于 `.env`。

校园网账号密码和 Wi-Fi 密码是两套凭据。脚本从此处读取校园网账号密码；Wi-Fi 密码由 Windows 自己保存和使用。Git 忽略规则会排除此 `.env`，但忽略并不等于加密。

## 启动和停止

从此子项目目录启动：

```powershell
python auto_login.py
```

也可以双击 `start.cmd`。脚本启动后立即检查一次，随后每分钟检查。保持控制台窗口打开；按 `Ctrl+C` 或关闭窗口停止。脚本用文件锁阻止同一目录启动多个实例。

三种单次操作：

```powershell
python auto_login.py --once
python auto_login.py --login-once
python auto_login.py --diagnose
```

- `--once`：按正常逻辑检查并在需要时尝试恢复。联网时不提交凭据。
- `--login-once`：主动提交一轮登录，即使外网探测正常也会发送登录请求，随后验证外网。用于接口诊断时，应先停止长期监测窗口。
- `--diagnose`：只读取 Wi-Fi 状态、外网探测和认证内网页面/配置，不提交密码。

双击 `login_once.cmd` 可运行主动登录；双击 `diagnose.cmd` 可运行只读诊断。单次命令恢复成功后返回码为 0；失败时返回 1。

### 隐藏任务计划程序的黑色命令窗口

如果任务操作当前运行 `python.exe`，会显示控制台窗口。改用同一 Python 安装目录下的 `pythonw.exe` 即可隐藏窗口。此工作区的可用路径是：

```text
C:\Users\jiell\miniconda3\pythonw.exe
```

在任务属性的“操作”中，将“程序或脚本”改为该路径；“添加参数”和“起始于”保持原值，例如参数为 `auto_login.py --once`，起始于本目录。请不要把程序改成 `start.cmd`，因为批处理会再次调用 `python.exe` 并显示窗口。隐藏窗口后，状态请查看 `auto_login.log`。

如使用其他配置文件，可通过参数指定：

```powershell
python auto_login.py --once --env-file "C:\path\to\ouc.env"
```

## 三次失败保护

默认 10 分钟内连续三轮登录都没有恢复外网时，停止访问校园网的认证页面、脚本、配置及登录接口。任务计划程序仍可每两分钟执行 `--once`，但暂停期间只进行外网探测；不会继续向校园网发送认证请求。`--login-once` 也受到此保护限制，`--diagnose` 会报告暂停状态并跳过认证接口读取。

计数按一轮认证流程计算：读取认证页、脚本、配置、提交登录，最多四个校园网请求组成一轮。服务器拒绝、超时、响应错误，或服务器返回成功但外网仍未恢复，都计为失败。Wi-Fi 尚未连接时，不计入登录失败。一旦实际检测到外网可用，全部计数和暂停标记会清除。

计数存放在子项目根目录的 `.login_retry_state.json`，仅包含尝试的时间戳和暂停标记；不包含账号密码。每轮请求前先保存计数，所以即使进程被终止或电脑重启，也不会绕过上限。文件读写失败或内容损坏时，脚本停止认证并写入日志，避免在状态不明时重复请求。该文件已加入 Git 忽略规则。

10 分钟是达到上限前的失败统计窗口，**不是暂停后自动重试的倒计时**。达到上限后，只有检测到外网恢复或明确手动重置，才会允许再次认证。修复账号密码或校园网故障后，可在子项目目录执行：

```powershell
python auto_login.py --reset-failures
```

此命令只清除本地状态，不联网、不提交密码。长期监测实例正在运行时，先停止实例后重置。任务计划程序现有的 `pythonw.exe` 和 `auto_login.py --once` 参数可继续使用。

## 检查和认证方式

外网检测对 Microsoft 和 Apple 的联网探测端点检查 HTTP 状态和预期正文。重定向、认证页、服务器错误或请求异常均视为未联网；任一探测返回预期内容即可认为外网可用。

外网不可用时，脚本使用 Windows `netsh wlan` 读取并连接 SSID。若无法确认目标 Wi-Fi 已连接，就不会向校园认证服务器发送账号密码。Wi-Fi 连接成功后，脚本先重新检查外网，以免执行不必要的认证。

需要登录时，脚本实时读取 `a79.htm`、`a41.js` 和 `page/loadConfig`，识别当前访客 IP、MAC、HTTPS 端口、认证方式、账号前缀和后缀，再向 Portal JSONP 登录接口提交认证。当前学校页面使用 HTTPS 端口 802 与 `login_method=1`；工具实现支持 Portal `login_method=1` 和 `9`。如果网站显示其他认证方式，脚本会安全停止，不会把密码送往猜测的接口。

上次排障发现，直接提交旧版 `/drcom/login` 路径会收到 HTTP 203 `Bad request`。现在根据 `a44.js` 的登录逻辑使用配置的 Portal 接口。遇到校园网页面升级、证书或登录协议变化时，查看诊断输出并更新实现。

## 日志和排障

日志保存在本目录的 `auto_login.log`，单文件约 1 MB，保留 3 个旧备份。日志记录检查结果、认证阶段、HTTP 状态、服务错误或超时信息；不会记录账号密码、认证 URL 或完整服务端响应。

建议排障顺序：

1. 停止长期监测窗口。
2. 双击 `diagnose.cmd`，查看目标 SSID 是否连上、外网是否可达，以及校园认证页面和配置接口是否能响应。若已触发三次失败保护，先排查已知原因，确认需要再认证时通过 `--reset-failures` 手动重置；诊断模式不会绕过保护。
3. 若认证配置可达，运行 `login_once.cmd`，检查服务器返回的认证结果和之后的外网探测。
4. 查看 `auto_login.log` 获取具体阶段与错误；分享日志前确认没有加入自己的备注或环境信息。

常见原因包括目标 Wi-Fi 不在范围内、Windows 没有已保存的 WLAN 配置、Windows 位置权限限制 SSID 查询、校园 DNS/认证服务器不可达、证书或时钟问题、账户密码错误、余额不足及账户设备数量限制。必要时以管理员终端运行以排查 Windows WLAN 权限。

服务器若返回“当前 IP 已经在线”，脚本仍会测试外网；探测成功即视为当前网络可用。脚本不会先注销已有会话。

## 验证

离线模拟测试位于 `tests/`。在子项目目录执行：

```powershell
python -m unittest discover -s tests -v
```

40 项模拟测试覆盖网络探测、SSID 确认、认证参数、HTTP 203、主动登录、密码日志保护，以及跨进程失败计数、三次暂停、网络恢复清零、手动重置和状态文件读写错误，不会操作真实 Wi-Fi，也不会注销在线会话。
