# OUNet · 中国海洋大学校园网自动登录

Android 与 Windows 发布名称统一为 **OUNet**，品牌 Logo、手机与桌面图标见 [品牌素材](../design/ounet/README.md)。

手机端已新增原生 Android 项目：安装、权限和构建步骤见 [Android APK 使用指南](ANDROID.md)，代码位于 `android/`。下文介绍 Windows Python 版本。

适用于 Windows 和已保存的 `HUAWEI-Jie_5G` Wi-Fi 配置。启动后立即检查一次，此后默认每 60 秒检查外网；断网时重连目标 Wi-Fi，读取认证配置并提交与网站一致的 Portal 登录请求，最后复查外网。目前网站实际登录接口为 `https://xha.ouc.edu.cn:802/eportal/portal/login`。

## 启动

安装 Python 3.10 或更新版本，在仓库根目录执行：

```powershell
python -m pip install -r requirements.txt
python auto_login.py
```

也可以双击本目录的 `start.cmd`。终端窗口需保持打开，按 `Ctrl+C` 停止。使用项目环境时可以执行 `uv run python auto_login.py`。

首次使用时，复制 `.env.example` 为 `.env`，填写自己的配置，并先在 Windows 中手动连接一次目标 Wi-Fi，保存无线网络密码。校园网账号密码与 Wi-Fi 密码是不同的配置；脚本使用 Windows 保存的 Wi-Fi 密码。

只运行一轮检查和恢复：

```powershell
python auto_login.py --once
```

外网可用时退出码为 `0`，未恢复或配置有误时为 `1`。此模式在已联网时不会发送登录请求。可以用 `--env-file 路径` 指定其他配置文件。

## 连续失败后停止认证

默认在 **10 分钟内连续三轮登录都未恢复外网后，暂停校园网认证请求**。暂停后仍会检查外网，但不再读取校园网认证页面、配置或提交登录；`--login-once` 和 `--diagnose` 也不能绕过暂停。单轮登录可能包含最多四个校园网 HTTP 请求，失败次数按登录流程计数，不按单个 GET 请求计数。

失败次数保存在本目录的 `.login_retry_state.json` 中，只包含时间戳及暂停标记。任务计划程序即使每两分钟重新启动 `--once`，或电脑重启，仍会保留计数。Wi-Fi 未连接成功不计入登录失败；已开始认证但被中断时，会保守地计为一次未恢复网络的尝试。

检测到外网恢复后自动清零。达到上限后不会因为过了 10 分钟而自行重试；修复密码或网络配置后，可以手动解除暂停：

```powershell
python auto_login.py --reset-failures
```

重置操作不发送网络请求。长期监测正在运行时，先按 `Ctrl+C` 停止，再执行重置和重新启动。已有的任务计划程序 `auto_login.py --once` 设置无需修改。

## 单次登录与断网诊断

先按 `Ctrl+C` 停止旧脚本，避免重复实例。三个双击入口分别为：

| 文件 | 用途 |
| --- | --- |
| `start.cmd` | 启动每分钟检查的长期监测 |
| `login_once.cmd` | 主动提交一次登录、复查外网并退出；已联网时也提交，可用于验证接口 |
| `diagnose.cmd` | 只读取 Wi-Fi 状态、外网探测结果、认证页面及配置，不提交密码 |

也可在仓库根目录执行：

```powershell
python auto_login.py --login-once
python auto_login.py --diagnose
```

这些脚本在本机运行，不依赖 Codex 或助手保持在线。断网时仍需要连接目标 Wi-Fi，并能访问校园内网认证服务器；此时不需要安装依赖或重新下载脚本。日志位于本目录的 `auto_login.log`，会区分页面读取失败、配置接口失败、HTTP 错误、证书错误、超时及服务器登录拒绝。

2026-10-03 验证：旧 `/drcom/login` 接口实测返回 HTTP 203 和 `Bad request(2)`；修正版主动提交实际 Portal 接口，服务器返回“当前 IP 已经在线”，随后外网检测正常。这证明请求已到达正确接口；真实断网恢复仍需在本机断网状态下验证。

## 配置与行为

| 配置项 | 默认值 / 用途 |
| --- | --- |
| `OUC_USERNAME` | 校园网账号 |
| `OUC_PASSWORD` | 校园网密码，开头的 `0` 会保留 |
| `OUC_SSID` | `HUAWEI-Jie_5G` |
| `OUC_WIFI_PROFILE` | `HUAWEI-Jie_5G`，Windows 保存的 Wi-Fi 配置名称 |
| `OUC_INTERVAL` | `60` 秒，最小 `10` 秒 |
| `OUC_TIMEOUT` | 每个网络请求超时 `8` 秒，最大 `30` 秒 |
| `OUC_FAILURE_WINDOW` | 失败统计窗口，默认 `600` 秒（10 分钟），必须大于 0 |

同名环境变量优先于 `.env`。配置文件按 UTF-8 读取，不需要 `python-dotenv`。

- 用 Microsoft 和 Apple 的外网探测页面检查实际响应内容；重定向到认证页面不算联网，任一探测成功即认为联网。
- 网络正常时不重连 Wi-Fi、不重复登录。断网时会尝试切换到指定 Wi-Fi；只有确认连接目标 SSID 后才提交校园网凭据。
- 网络请求直接访问，不使用系统环境中的代理；HTTPS 证书校验保持开启。
- 每次登录先读取 `a79.htm`、`a41.js` 和 `page/loadConfig`，获取当前 IP、HTTPS 端口、认证方式及账号前后缀；不固定使用上次的 IP。
- 未达到连续三轮失败上限时，失败后下一轮继续重试；达到上限后停止认证，等待外网恢复或手动重置。重连和认证较慢时，检查间隔可能超过 60 秒。
- 登录保护状态无法读取或保存时，也会停止校园网请求，以免失败次数丢失后无限重试。
- 同一目录只允许一个实例运行。日志写入 `auto_login.log`，最大约 1 MB，保留 3 个备份；不记录密码、登录请求 URL 或完整认证响应。
- `.env` 是本机明文配置，请勿分享；它和包含原始凭据的 `prompt/start.md` 均已加入 Git 忽略规则。

## 无法恢复时

检查目标 Wi-Fi 是否可用、Windows 是否保存了该 Wi-Fi 配置，以及账号密码、余额、设备数量限制。若提示 Wi-Fi 查询失败，检查 WLAN 服务和 Windows 的位置权限；必要时在管理员终端运行。脚本不会通过注销当前会话来强制重新登录。

当前实现依据该网站实际加载的 `a40.js`（含 `a44.js` 的 Portal 登录覆盖逻辑）及实时认证配置，支持 `login_method=1/9`。上一版只读取 `a43.js` 的基础登录逻辑，遗漏了后续覆盖，已修正。若学校更换认证系统或启用验证码，需要更新登录实现。

## 验证

Windows 桌面版已更新到 **0.3.0 测试版**，采用新的四页界面，支持外网优先、只读身份刷新、小数秒间隔、独立密码错误保护、在线设备、请求统计和诊断抽屉，见 [安装与使用](WINDOWS.md)、[开发与构建](../desktop/README.md)和[v0.3 实施验证](WINDOWS_V0_3_VERIFICATION.md)。安装包位于 `desktop/dist/`。独立 CLI 保持下文原规则；显式迁移接管后，旧 CLI 不再发送请求，请使用桌面版。新旧入口保留运行锁，历史规划保留供追溯。

在仓库根目录执行离线测试：

```powershell
python -m unittest discover -s tests -v
```

40 项测试通过模拟响应覆盖断网、Wi-Fi 重连、实际 Portal 请求字段、动态 IP、HTTP 203 错误、登录失败、主动登录、断网诊断、外网复查、密码日志保护，以及跨进程失败计数、三次暂停、恢复清零、手动重置和状态文件错误；不会操作真实 Wi-Fi 或注销真实会话。
