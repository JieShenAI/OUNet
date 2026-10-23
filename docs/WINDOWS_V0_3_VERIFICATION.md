# OUNet Windows 0.3.0 · 实施与验证

日期：2026-10-23（Asia/Shanghai）。本轮按 [v0.3 设计规划](WINDOWS_DESKTOP_V0_3_DESIGN_PLAN.md)更新真实 Python / QML 应用；不是 HTML 套壳。Android 与独立 CLI 的业务逻辑没有修改。

## 实施范围

| 阶段 | 实施结果 |
| --- | --- |
| 状态与协议 | 将 XHA 在线、未登录、未知与详情失败拆开；在线状态未知不提交密码；保留已核实的六字段映射 |
| 网络上下文 | Native IP Helper 枚举当前接口；IPv4 请求同时绑定源 IP 与 `IP_UNICAST_IF`；不按列表首项猜多无线接口；不扫描或切换 Wi-Fi |
| 设置与凭据 | 设置 v3，整数毫秒存储，旧秒数按原值迁移；DPAPI、备注、随机凭据版本、保守保留旧拒绝与全局暂停 |
| 连接引擎 | 自动外网通过即结束；独立自动/主动重连/只读身份意图；认证最多一次；成功后稳定复检再记恢复 |
| 请求保护 | 实际请求三连败全局暂停；手动一次试探；凭据错误独立绑定账号与版本；停止错误凭据的自动 XHA 全链路 |
| 调度与并发 | 一个执行器，完成后再等待；手动意图合并；网络变化取消并废弃旧结果，不回放历史周期 |
| UI | 新浅色侧栏和四个真实页面、主题 token、深色/系统外观、高对比色分支、完整 IPv6 换行与复制 |
| 系统行为 | 保留单实例、托盘、用户级自启、旧 CLI 接管和安装身份；托盘主动连接与主界面使用同一命令 |
| 设备与诊断 | 真实只读设备查询、按当前 uid 分页；诊断最新置顶、筛选、读历史时保持位置、本地轮询随抽屉关闭停止 |
| 更新 | 手动读取固定仓库的 Windows 正式发布；检查平台、版本、精确安装包 URL 与 SHA-256；只打开浏览器，不静默执行 |

应用可确认单一 Wi-Fi IPv4，或无可用 Wi-Fi 时的单一 Ethernet IPv4；多个无线接口、无法确认地址与 IPv6-only 场景给出说明并停止凭据提交。Native 枚举和 socket 选项在当前机器只读/未连接 socket 上核对过；这不等于所有 VPN、桥接和真实有线认证已验证。

## 已执行的检查

### 离线回归

**94 项桌面测试通过，40 项独立 CLI 回归通过。** 测试使用临时目录、虚构凭据、模拟 HTTP 和 mock 网卡，不调用真实门户、不修改真实自启。

- 模拟 600 轮健康自动检测：XHA 状态、认证、会话查询均为零；不会因为首页缺六字段而自动查询。
- 明确密码错误后模拟 20 轮离线自动检测：XHA 识别、状态与认证均不再调用；普通外网探测保留。
- 只读刷新未登录不提交密码；已在线但外网失败不重新认证；未知状态与缺 uid 不冒充未登录。
- 全局第三次实际 HTTP 失败后剩余请求为零；暂停跨读取保留；只读身份、设备、更新入口不能绕过。
- 不足一秒 / 非法间隔、旧间隔保留、凭据版本隔离、保存同一密码、备注不解除错误等通过。
- 忙碌时多次主动点击只执行一个操作；自动任务后只保留一个主动意图；网络变化后旧身份不写回。
- 请求统计计派发、包含失败、排除公共与未派发、使用单调时钟滚动 60 秒；新进程清零。
- 实际密码留空保留、DPAPI 往返、密文不含虚构明文、配置中断标记、删除选中账号不静默切换均通过。
- Windows 更新平台与固定下载路径校验、Android 发布排除与暂停门禁通过。

测试入口：`desktop/tests/test_desktop.py`、`test_v02.py`、`test_controller.py`、`test_v03.py`、`test_controller_v03.py`。

### 真实 QML

源码离屏加载四页成功，**QML 警告为零**。DPAPI、Decimal、SSL 与 TLS 验证开关检查通过。

真实信号/绑定交互检查通过：本地账号切换不改变在线身份；密码字段为空且星号仅占位；忙碌禁用控件；未保存编辑不被后台通知覆盖；0.3 秒设置；全局暂停保留；深色主题与主按钮文字对比；设备抽屉；诊断最新置顶。

在 1440 / 1200 / 1008 / 820 / 640 逻辑宽度导出截图，完整 IPv6 字段可布局。可查看 [交互检查报告](../desktop/screenshots/v0.3-ui/ui-report.json)与[四页加载报告](../desktop/screenshots/smoke-report.json)。

![真实 QML 概览，全部为模拟数据](../desktop/screenshots/v0.3-ui/overview-desktop.png)

[密码错误](../desktop/screenshots/v0.3-ui/password-error.png) · [全局暂停](../desktop/screenshots/v0.3-ui/requests-paused.png) · [深色设置](../desktop/screenshots/v0.3-ui/dark-settings.png) · [在线设备](../desktop/screenshots/v0.3-ui/online-devices.png) · [640 宽度](../desktop/screenshots/v0.3-ui/overview-640.png)

### Native 只读检查

当前机器成功枚举可确认的无线 IPv4 接口。SSID 因系统接口限制未返回，不阻断独立接口识别。创建未连接 socket 后，源地址 bind 成功，读取 `IP_UNICAST_IF` 返回预期接口索引；未连接远端、未提交认证。

实现参考 Microsoft [GetAdaptersAddresses](https://learn.microsoft.com/en-us/windows/win32/api/iphlpapi/nf-iphlpapi-getadaptersaddresses)和[IPv4 socket 选项](https://learn.microsoft.com/en-us/windows/win32/winsock/ipproto-ip-socket-options)。GitHub 更新处理官方文档规定的 [200 / 302 附件响应](https://docs.github.com/en/rest/releases/assets?apiVersion=2022-11-28#get-a-release-asset)，只允许固定附件主机的一次 HTTPS 跳转。

### 打包与产物

完整构建已通过：PyInstaller、四页独立 EXE 离屏加载、移除开发环境 PATH 后的运行检查、Inno Setup 编译、SHA-256 和 Windows 更新元数据生成。首次打包发现图标目录被展平，修复后已重新执行完整构建，独立 EXE 的 QML 警告为零。

产物：[0.3.0 安装包](../desktop/dist/OUNet-0.3.0-Setup-x64.exe)、[SHA-256](../desktop/dist/OUNet-0.3.0-Setup-x64.exe.sha256)、完整 `desktop/dist/OUNet/`、[Windows 更新元数据](../desktop/dist/windows-update.json)。安装包约 44 MiB。构建日志位于 `desktop/.cache/build-v03.log`，干净运行检查位于 `desktop/.cache/clean-runtime-report.json`。本轮不发布远程 Release，不安装到真实用户环境。

## 真实环境仍待验收

- 当前机器检测到已注册的真实 OUNet 安装；自启注册项的读取被环境限制，未据此推断自启关闭。未运行会覆盖真实注册项的安装/升级/卸载集成测试；安全脚本仍会遇到既有安装或自启即停止。
- 未主动断网、切 Wi-Fi、提交真实账号密码或注销会话；真实校园认证、稳定恢复、错误响应与在线设备大数据分页仍需实机验收。
- 已沿用此前核验的 XHA 字段协议，本轮没有新增一次真实门户字段抓取。真实门户更换或返回未知结构会停止并给出原因。
- 更新本地代码、校验与构建元数据完成后，远端尚未发布时会显示尚无 Windows 正式版本；本轮未做真实远程下载或安装。
- 单一 Ethernet 的代码路径已接入；真实有线、VPN、多个活动接口、系统重连、IPv6-only 与不同网卡驱动尚未完整验收。
- 普通宽度、浅深色与键盘路径有自动检查；真实 125%/150%/200% DPI、屏幕阅读器、高对比系统开关、锁屏、睡眠和开机自启仍需 Windows 环境验收。
- 安装器未代码签名；不把哈希元数据等同于代码签名或已下载安装包的自动校验。用户通过浏览器下载后可与提供的 SHA-256 比对。

## 与设计稿的实现边界

保留 Windows 原生窗框，截图只显示客户区。正式 QML 的字号和留白允许内容滚动，未用固定截图高度裁剪字段。窗口首次按可用屏幕收缩；暂未加入持久记忆窗口位置。独立账号详情在窄窗改为上下布局；设备/诊断沿用右侧模态抽屉。没有动画装饰、强制下线、后台静默更新或恢复旧目标 Wi-Fi 切换。

源代码、运行说明和设计稿分别保留，以真实实现和本验证记录描述当前产物。
