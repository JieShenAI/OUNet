# 迁移验证

日期：2026-10-23。验证对象为独立 OUNet 仓库的源码；所有认证测试使用模拟响应与临时数据。

| 检查 | 结果 |
| --- | --- |
| Python CLI 单元测试 | 40 项通过 |
| Windows 桌面单元测试 | 94 项通过 |
| Windows QML 交互检查 | 通过，离屏模式、隔离数据、无网络 |
| Android Debug 单元测试 | 15 个测试类，145 项通过，0 失败、0 跳过 |
| Android Debug Lint | 通过，No issues found |
| 待提交源码凭据扫描 | 未发现本地账号密码、令牌模式或私钥 |

Python / QML 使用既有桌面虚拟环境；Android 使用 JDK 21、Gradle 8.9、SDK 35 与本地缓存离线执行。
执行 `:app:testDebugUnitTest :app:lintDebug --offline --no-daemon`。
Java 8 source/target 及已有弃用 API 的编译警告仍保留。

本次验证不代表真实校园网认证、Android 后台保活、Windows 重启/休眠或安装包覆盖升级已完成验收。
未重新构建或发布正式安装包。迁移前的本地产物仍保留，发布前须从新仓库重建。
