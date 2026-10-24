# 更新记录

Android 与 Windows 分别使用 `android-vX.Y.Z` 和 `windows-vX.Y.Z` 标签。

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
