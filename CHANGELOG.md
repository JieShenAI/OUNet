# 更新记录

Android 与 Windows 分别使用 `android-vX.Y.Z` 和 `windows-vX.Y.Z` 标签。

## Windows 0.3.6（已发布）

- 保留 0.3.5 功能，使用独立官方 CPython 和官方摘要锁定的固定依赖重新构建。
- 程序目录、Setup 和 ZIP 增加 Defender 扫描门槛及随包扫描报告，任一步失败时阻断发布。
- [发布说明](docs/WINDOWS_V0_3_6_RELEASE_NOTES.md) · [验证记录](docs/WINDOWS_V0_3_6_VERIFICATION.md)。

## Windows 0.3.5（发布准备）

- 直接显示上次读取的校园账号和六项会话信息，无需展开历史，不再显示“校园身份尚未读取”。
- 网络变化、未登录或刷新失败时保留旧快照与原读取时间，成功读取新账号后再替换。
- [发布说明](docs/WINDOWS_V0_3_5_RELEASE_NOTES.md) · [验证记录](docs/WINDOWS_V0_3_5_VERIFICATION.md)。

## Android 1.3.6 / Windows 0.3.4（发布准备）

- 临时故障三次后自动复检，按15秒、30秒、1分钟、2分钟、5分钟逐级等待，上限后持续复检。
- 网络事件可提前触发受限复检，无事件仍保留定时恢复；重启保留冷却进度。
- 明确凭据错误、用户关闭守护、本地数据异常及旧入口安全暂停保持独立保护。
- 隔离更新、外网探测、XHA传输与认证结果，后台检测仍可编辑账号。
- [设计与验证](docs/AUTOMATIC_RECOVERY_VERIFICATION.md)。

## Windows 0.3.3（发布准备）

- 后台检测期间可以添加、切换和编辑本机账号，账号、密码及备注不再丢失焦点或中断中文输入。
- 仅在实际配置写入时锁定编辑器，保存、删除和认证仍使用原串行保护。
- 包含 0.3.2 的按钮闪烁修复。
- [发布说明](docs/WINDOWS_V0_3_3_RELEASE_NOTES.md) · [验证记录](docs/WINDOWS_V0_3_3_VERIFICATION.md)。

## Windows 0.3.2（发布准备）

- 修复所有页面按钮在自动检测时反复变灰、切换文字及丢失焦点。
- 忙碌时使用输入拦截，保留按钮外观与重复操作保护；账号按钮不再随无变化通知重建。
- [发布说明](docs/WINDOWS_V0_3_2_RELEASE_NOTES.md) · [验证记录](docs/WINDOWS_V0_3_2_VERIFICATION.md)。

## Android 1.3.5 / Windows 0.3.1（发布准备）

- 新增联系作者、邮箱复制、系统邮件、开源地址和 GitHub 下载页。
- 完善本平台更新检查的分页、附件一致性、重复字段、失败状态与冷却。
- 新增 Windows 免安装 ZIP、精确资产清单及可验证的 GitHub 草稿发布流程。
- [实施验证与待验收范围](docs/RELEASE_UPDATE_VERIFICATION.md)。

## 仓库迁移记录

- 将校园网相关源码从 `JieShenAI/tools` 拆分为独立 OUNet 仓库。
- 统一 MIT 许可证，补齐贡献指南、凭据说明、问题模板、PR 模板和持续集成。
- 调整独立仓库的文档、构建路径与 Android / Windows 更新地址。
- 保留 `archive/ouc_auto_net/` 旧 CLI 快照。

## 已有源码版本

- Android 1.3.4：[发布说明](docs/ANDROID_V1_3_4_RELEASE_NOTES.md)。
- Windows 0.3.0 测试版：[实施与验证](docs/WINDOWS_V0_3_VERIFICATION.md)。

本次目录迁移未创建二进制 Release，也未改变既有 Android 包名、签名或 Windows 安装标识。
