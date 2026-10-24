# OUNet Windows Desktop

版本 **0.3.2（待公开发布）** · Python / PySide6 / QML · Windows 10 1809+ / Windows 11 x64。

[使用说明](../docs/WINDOWS.md) · [v0.3 设计规划](../docs/WINDOWS_DESKTOP_V0_3_DESIGN_PLAN.md) · [v0.3 实施与验证](../docs/WINDOWS_V0_3_VERIFICATION.md)

0.3.1 新增联系作者、开源项目与 GitHub 下载入口，完善正式版检查和发布打包。[发布说明](../docs/WINDOWS_V0_3_1_RELEASE_NOTES.md) · [实施与待验收](../docs/RELEASE_UPDATE_VERIFICATION.md)。

0.3.2 修复后台检测引起的按钮闪烁，保留样式、焦点与重复操作保护。[发布说明](../docs/WINDOWS_V0_3_2_RELEASE_NOTES.md) · [修复验证](../docs/WINDOWS_V0_3_2_VERIFICATION.md)。

## 交付与行为

- [安装包](dist/OUNet-0.3.2-Setup-x64.exe)与同目录 SHA-256；免安装可使用 `dist/OUNet-0.3.2-Windows-x64-portable.zip`，需完整保留 `dist/OUNet/`，运行 `OUNet.exe`。不依赖用户安装 Python。
- 四个真实页面：连接概览、账号管理、偏好设置、帮助与关于；浅/深色、收起导航、在线设备与诊断抽屉。
- 启动与自动监测优先验证外网，通过后 XHA 请求为零；主动连接、只读身份、设备分页是独立意图。
- 请求绑定当前可确认接口与源 IPv4；取消目标 SSID/profile 选择及自动切网。IPv6-only、VPN、桥接与真实有线认证仍需实机验证。
- 默认完成后等待 1 秒，最小 0.3 秒、最多三位小数；升级保留原间隔。只用一个工作执行器，不补发积压周期。
- 全局实际 HTTP 三连败暂停保留，手动只放行一次试探；明确凭据错误绑定账号与凭据版本独立保存。
- DPAPI 多账号、空密码保留、备注、下次认证账号与在线身份分开；删除当前账号不静默换号。
- 开机范围恢复台账、稳定复检后入账、进程范围 XHA 尝试总数和最近 60 秒统计。
- 在线设备只读、按当前 XHA uid 查询、分页失败保留已读取记录；无强制下线操作。
- 最新在前的本地诊断，阅读历史不抢位置；离线说明与手动 Windows 更新，更新也受全局门禁控制。

当前产物未代码签名。自动化验证不代表真实校园认证、Windows 重启、睡眠和所有网卡环境已验收。构建不安装、不写自启、不提交真实认证、不远程发布。

## 开发与验证

使用本目录独立环境：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-build.txt
.\.venv\Scripts\python.exe tools\prepare_assets.py
.\.venv\Scripts\python.exe run.py --demo
```

`--demo` 使用临时模拟数据，不读取真实账号、不联网、不切网、不更改 Windows 设置；演示模式不保存凭据。普通运行使用 `%LOCALAPPDATA%\OUCNetworkAssistant\`。

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -q
.\.venv\Scripts\python.exe -m unittest discover -s ..\tests -q
.\.venv\Scripts\python.exe run.py --smoke-test --smoke-output screenshots
.\.venv\Scripts\python.exe tools\test_ui.py
.\build.ps1
```

构建依次进行素材、许可、测试、源码 QML、交互检查、PyInstaller、移除开发路径的独立 EXE 检查、Inno Setup、SHA-256 与 `windows-update.json`。固定依赖见 `requirements.txt` / `requirements-build.txt`。编译器默认 `.toolchain/inno/ISCC.exe`，可用 `-IsccPath` 指定。

`tools/test_install.ps1` 是隔离安装检查，存在真实安装或自启时主动停止；不要为了通过检查而停用现有自启。

## 模块

| 模块 | 职责 |
| --- | --- |
| `controller.py` | QML 状态、统一执行器、意图合并、配置与迁移、设备与更新入口 |
| `engine.py` | 外网优先、三态校园登录、一次认证与稳定复检 |
| `network.py` | Native IP Helper 接口枚举、源 IPv4 与 `IP_UNICAST_IF` 绑定 |
| `request_policy.py` | 每个实际 HTTP 结算一次、全局暂停、独立凭据错误、请求统计 |
| `portal_status.py` | 核实过的门户配置、登录状态、本机与设备记录 |
| `auth_error.py` | 明确凭据错误分类，不猜错误码 |
| `storage.py` | 设置 v3、毫秒间隔、账号备注/凭据版本、DPAPI、原子写入和日志 |
| `recovery.py` | 按开机、连接和故障 ID 去重的恢复台账 |
| `update.py` | 固定仓库 Windows 正式发布与元数据校验，只打开浏览器下载 |
| `qml/` | token、壳、四页、复用组件、抽屉与键盘交互 |

## 发布约定

构建生成但不上传：`OUNet-0.3.2-Setup-x64.exe`、`.sha256`、`windows-update.json`。手动发布应使用 `windows-v0.3.2` 标签，同一 Release 上传三个文件；运行中的更新入口只接受该 Windows 命名规则，不混入 Android Release。

保持既有 AppId、安装目录、数据目录、自启注册名称、单实例锁和 CLI 接管标记；Android 和独立 CLI 的业务规则没有变更。
