# OUNet 双端联系、开源与更新实施记录

日期：2026-10-23（Asia/Shanghai）。目标 Android 1.3.5 / versionCode 9、Windows 0.3.1。

## 已实现

- Android 设置新增关于区域：完整邮箱、发送邮件、复制邮箱、开源仓库、GitHub 下载页及复制入口。
- Windows 帮助与关于增加对应卡片、复制与系统调用；安装器增加源码、支持和更新链接。
- 两端使用固定公开信息常量；联系行为只打开系统邮件编辑器，不发送邮件、不附加账号或日志。
- GitHub 更新保持平台独立渠道，增加有界分页、附件核对、重复 JSON 字段拒绝、版本状态、限流和未完成提示。
- Windows 查询仍经过全局请求保护；公开链接和本地复制不会改变暂停状态。Android 更新对话框独立刷新，不受校园检查间隔影响。
- Android 沿用原正式签名和兼容 APK 名称，正式构建不再自动生成新密钥；APK 实际包名、版本、minSdk 与元数据交叉检查。
- Windows 增加版本一致性检查、完整 portable ZIP；两端使用精确发布资产暂存和构建清单。
- 新增可重复的 GitHub 草稿构建流程及发布器，逐文件读取草稿验证；显式公开后匿名下载回读，失败自动撤出正式渠道。

## 本地自动验证

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| Android JUnit / Robolectric | 170 项通过 | 原有 161 项，加 8 项更新查询与 1 项关于入口检查 |
| Android Lint | 无问题 | Debug 检查；Release 构建同时执行既有检查 |
| Android 正式构建 | 通过 | 原证书、实际包身份/版本/minSdk、兼容包摘要核对 |
| Windows 桌面测试 | 107 项通过 | 原有 94 项扩展，含分页、附件、跳转、冷却和联系入口 |
| CLI 与发布工具测试 | 46 项通过 | 原有 40 项，加 6 项发布资产/来源/篡改与草稿门禁检查 |
| 真实 QML 交互 | 通过 | 合成数据、复制邮箱、系统邮件/下载页调用 mock，无真实发信 |
| 冻结 EXE 独立运行 | 通过 | 移除开发 Python/Qt PATH，QML、DPAPI、TLS 和四页加载正常 |
| Windows 旧数据兼容 | 通过 | 从 `e3fc48e` 的 0.3.0 代码创建模拟 DPAPI 账号、设置与暂停；新版读取正确且未改原文件 |
| 工作流 YAML | 本地解析通过 | 解析不等于 GitHub runner 已执行，远程状态另行记录 |

Windows 数据兼容测试使用临时目录和虚构账号，不改动真实数据、自启或系统网络。源码的精确提交、文件摘要、大小及自动检查标志记录于每个 Release 的 `release-manifest.json`。

截图在本地 `android/.toolchain/qa/about-project-api35.png`、`desktop/screenshots/v0.3-ui/help-contact-and-source.png`；未将生成图提交源码。

## 正式发布前尚需完成

1. Android 真机从 1.3.4 覆盖到 1.3.5，确认账号、设置和恢复记录保留。当前环境没有连接 Android 设备；Robolectric 不能替代系统安装验证。
2. Windows 真实安装器从 0.3.0 覆盖到 0.3.1。本机已有注册安装，既有安装测试会拒绝干预，未执行真实覆盖或卸载；已完成独立 EXE 与隔离模拟数据兼容检查。
3. Windows 10/11 环境矩阵、无邮件客户端/浏览器处理器、高 DPI 和手机大字体的完整人工验收。已执行的系统调用失败为 mock 检查。
4. 草稿公开后，匿名下载与旧/新客户端真实发现检查；草稿阶段不能声称匿名下载已可用。

按[更新规划](CONTACT_OPEN_SOURCE_AND_RELEASE_UPDATE_PLAN.md)保留正式发布前的覆盖升级门槛。功能和构建交付不代表上述设备项目已经通过；当前阶段优先准备经过字节核验的发布草稿。后续完成设备验收或明确调整发布范围后，按[发布手册](RELEASING.md)公开发布。

## 验证命令

```powershell
python -m unittest discover -s tests -q
desktop/.venv/Scripts/python.exe -m unittest discover -s desktop/tests -q
desktop/.venv/Scripts/python.exe desktop/tools/test_ui.py
desktop/.venv/Scripts/python.exe desktop/tools/test_upgrade_data.py --from-ref e3fc48e
./android/build.ps1
./desktop/build.ps1
python tools/release_bundle.py --verify dist/releases/android-v1.3.5
python tools/release_bundle.py --verify dist/releases/windows-v0.3.1
```
