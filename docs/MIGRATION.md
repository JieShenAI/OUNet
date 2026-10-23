# 独立仓库迁移记录

迁移日期：2026-10-23（Asia/Shanghai）。来源仓库：[JieShenAI/tools](https://github.com/JieShenAI/tools)，来源基线 `57cb762`，同时保留迁移时工作区中尚未提交的 OUNet 修改。

| 原目录 | OUNet 中的位置 |
| --- | --- |
| `auto_login_ouc/` | 仓库根目录 |
| `auto_login_ouc/android/` | `android/` |
| `auto_login_ouc/desktop/` | `desktop/` |
| `auto_login_ouc/docs/`、`design/`、`tests/`、`prompt/` | 同名目录 |
| `auto_login_ouc/README.md` 中的 CLI 说明 | `docs/CLI.md` |
| `.github/workflows/ouc-android.yml` | `.github/workflows/android.yml` |
| `ouc_auto_net/` 旧副本 | `archive/ouc_auto_net/` |

目标本地目录：`C:\Users\jiell\github\OUNet`。独立仓库从本次导入提交开始记录版本；此前历史保留在来源仓库。

## 本地配置与构建环境

原目录中的 `.env`、签名材料、运行状态、构建产物与工具链随目录保存在本地，Git 不跟踪这些文件。
来源仓库的根 `.env` 与 `.venv` 供其他工具使用，保留在原处。
Gradle 的 `local.properties` 更新到新 SDK 路径。Python 虚拟环境中的命令启动器可能含旧绝对路径，优先使用 `python -m pip`；必要时按对应 README 重建环境。

任务计划、自启或快捷方式若仍指向旧源码路径，需在使用时改为新路径。已安装桌面应用使用原有数据目录、安装标识与自启名称，不随源码目录改名。
此前运行中的 CLI 应先正常停止，再从新目录启动；凭据与保护状态保留，迁移不会主动登录或切换网络。

## 更新与发布

Android 与 Windows 的源码更新入口及元数据构建工具已指向 `JieShenAI/OUNet`。
已安装旧版本仍会访问原更新源，需要手动安装后续新构建；如需连续升级，应在原仓库发布经过验证的过渡版本。
本次未复制或发布原仓库的 Release，旧二进制和历史验证记录不代表新仓库已完成发布。
本地 `dist/` 中的已有产物是迁移前构建，发布前须从新源码重建并验证元数据。

历史规划和旧快照用于追溯，不视为当前功能或新路径的运行承诺。
