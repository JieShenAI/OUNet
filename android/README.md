# OUNet Android 1.3.5

应用名称与启动图标已统一为 **OUNet**，当前源码版本为 1.3.5。新构建主包为 `dist/OUNet-1.3.5-release.apk`；同时保留原文件名兼容包供旧版更新入口使用。素材、导出方式和兼容说明见 [OUNet 品牌素材](../design/ounet/README.md)。

1.3.5 新增联系作者、开源地址和 GitHub 下载入口，并完善手动更新检查。[发布说明](../docs/ANDROID_V1_3_5_RELEASE_NOTES.md) · [实施与待验收范围](../docs/RELEASE_UPDATE_VERIFICATION.md) · [发布手册](../docs/RELEASING.md)。

原生Java应用，Android 9起可用。包名与原正式签名保持，源码位于app/src/main。自有代码采用 [MIT 许可证](../LICENSE)，第三方组件与素材保留各自许可。

已实现只读Wi-Fi名称、XHA校园服务识别与按需认证、加密多账号、在线六字段、设备分页、恢复台账、XHA请求统计，以及设置中的离线说明和GitHub更新检查。默认1秒、最小0.3秒是本地评估节拍；断网轮换探测，取消每分钟配额和失败退避。XHA可用的账号密码表单即为校园网依据。

1.3.2取消固定60秒认证冷却。主按钮立即重置失败保护，强制刷新XHA在线身份；确认未登录后认证，在线但外网异常时不重复登录。诊断最新置顶并自动更新；顶部缺权限显示“没有网络或位置权限”。

1.3.3增加持久的密码错误提示和修改入口。服务器明确拒绝且说明密码错误时，停止这组凭据的自动请求；修改实际密码后旧提示失效，或手动重新连接后再试。仅说明账号或密码错误时不擅自判断是哪一项。

1.3.4将外网探测放在XHA之前。启动和自动检测外网通过即结束，不查询XHA状态、不自动刷新到期详情；保留同一连接已读取的信息并标注读取时间。外网按设定间隔轮换探测服务；主动“重新检测并连接”、信息“刷新”和设备页操作仍按用户要求请求XHA。

1.3.2请求放大分析见[审计记录](../docs/ANDROID_V1_3_2_REQUEST_AUDIT.md)。本版处理明确凭据错误的重复请求；其他通用拒绝、未知状态等路径的调度仍未全面重构。

- [使用与构建](../docs/ANDROID.md)
- [1.3.4发布说明](../docs/ANDROID_V1_3_4_RELEASE_NOTES.md)及[实施验证](../docs/ANDROID_V1_3_4_FIX_VERIFICATION.md)
- [1.3.3发布说明](../docs/ANDROID_V1_3_3_RELEASE_NOTES.md)及[实施验证](../docs/ANDROID_V1_3_3_FIX_VERIFICATION.md)
- [1.3.2发布说明](../docs/ANDROID_V1_3_2_RELEASE_NOTES.md)、[实施验证](../docs/ANDROID_V1_3_2_FIX_VERIFICATION.md)及[原升级规划](../docs/ANDROID_V1_3_2_LOGIC_PLAN.md)
- [历史1.3.1修复记录](../docs/ANDROID_V1_3_1_FIX_VERIFICATION.md)
- [1.3实施、测试与未验收项](../docs/ANDROID_V1_3_IMPLEMENTATION.md)
- [1.3设计与规划](../docs/ANDROID_V1_3_DESIGN_PLAN.md)及[设计示意](../design/android/v1.3-overview.svg)
- [正式试装APK](dist/OUNet-1.3.5-release.apk)及[SHA-256](dist/OUNet-1.3.5-release.apk.sha256)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File android/build.ps1
```

脚本执行测试、Lint、release打包，核验原签名并生成dist/android-update.json和发布说明。密钥、口令与本机构建缓存均不提交仓库。构建不会自动发布GitHub Release。

发布时采用android-v1.3.5标签，将同一构建的正式APK、校验文件与android-update.json上传Release。旧版安装器使用兼容文件名 `ouc-autologin-1.3.5-release.apk`，发布时也需上传兼容APK和校验文件。现有用户下载后确认覆盖安装，无需先卸载；1.3起支持设置内检查Android发布并前往浏览器下载。源码提交本身不会更新手机软件。

小米/iQOO/vivo/OPPO后台、真实校园认证与真实覆盖安装仍需逐机验收；本地自动测试不等于真机通过。
