# OUNet Android 1.3.6

Android 9+，versionCode 10。沿用原包名与正式签名。

- 临时故障三次后暂时暂停，按15秒、30秒、1分钟、2分钟、5分钟自动复检，上限后继续每5分钟复检，恢复后继续守护。
- XHA连通性与登录结果分别记录；确认未登录后才提交认证，已登录时不重复提交密码。
- 网络重新连接可提前复检一次；没有网络事件也会定时复检。冷却进度跨重启保留。
- 明确账号密码错误仍保留提示；用户关闭监测后不自动认证。更新检查失败不影响认证保护。
- 更新设置、监测提示和离线说明；保留联系作者、开源地址与GitHub下载入口。

下载 `OUNet-1.3.6-release.apk`；旧客户端兼容附件为 `ouc-autologin-1.3.6-release.apk`，内容相同。已有正式版可确认覆盖安装，无需先卸载。

[验证与待验收范围](https://github.com/JieShenAI/OUNet/blob/main/docs/AUTOMATIC_RECOVERY_VERIFICATION.md)。Android后台及锁屏受系统调度影响，不能保证准点；真实校园认证、设备覆盖升级仍需实机验收。
