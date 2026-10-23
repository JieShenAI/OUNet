# OUNet 发布手册

Android 与 Windows 各自创建 Release：`android-vX.Y.Z`、`windows-vX.Y.Z`。默认客户端只发现非草稿、非预发布的本平台版本，不使用仓库级 Latest 定位安装包。

## 构建与准备

1. 将目标代码合入 `main`，锁定 CI 通过的完整源码 SHA；提升版本并添加对应发布说明。
2. Android 使用原正式密钥，执行 `android/build.ps1`；缺少签名配置立即失败。正式密钥和口令不得进入 Git。
3. Windows 在独立 `.venv` 安装 `requirements.lock.txt`，使用 Inno Setup 6.7.3，执行 `desktop/build.ps1`。
4. 两端脚本生成并验证 `dist/releases/<平台标签>/`；只上传此目录中的白名单文件，不能上传历史 `dist/*`。
5. Windows 可执行 `desktop/tools/test_upgrade_data.py --from-ref e3fc48e` 核对旧版模拟数据；该测试不等于真实安装器覆盖升级。

Inno Setup 固定版本及下载来源见[官方发布](https://github.com/jrsoftware/issrc/releases/tag/is-6_7_3)；工作流校验官方资产 SHA-256 后安装。

## GitHub 草稿

GitHub Actions 的 **Build and prepare OUNet Release** 接受平台、版本和 `main` 上的完整源码 SHA，执行构建、检查、上传并逐文件读取草稿核验，默认不公开发布。

也可使用已经验证的本机产物：

```powershell
python tools/release_bundle.py --verify dist/releases/android-v1.3.5
python tools/publish_release.py --bundle dist/releases/android-v1.3.5
python tools/publish_release.py --bundle dist/releases/windows-v0.3.1
```

发布器使用 Actions 的 `GITHUB_TOKEN`，本机则使用 Git Credential Manager 的 GitHub 凭据。凭据只保存在内存，不打印、不写发布附件。构建清单必须指向已推送的干净源码提交，且声明自动检查通过；已公开版本绝不覆盖。

Android 草稿包含品牌 APK、字节相同的 `ouc-autologin-*` 兼容 APK、两份摘要、`android-update.json`、发布说明及构建清单。Windows 草稿包含 Setup、完整 portable ZIP、两份摘要、`windows-update.json`、发布说明及构建清单。

## 公开发布与验收

完成本地检查、旧版覆盖安装和渠道评审后，给当前确切的源码与平台准备审核报告。例如：

```json
{
  "sourceCommit": "<40位源码SHA>",
  "tag": "android-v1.3.5",
  "approvedForPublication": true
}
```

审核报告不上传到 Release。只有对应构建已完成发布评审时才把 `approvedForPublication` 设为 `true`。然后执行：

```powershell
python tools/publish_release.py --bundle dist/releases/android-v1.3.5 --publish --review-report <审核报告路径>
```

发布器再次验证草稿附件，公开后用匿名请求读取 Release 并逐文件下载核对摘要。出现公开内容或协议验证失败时，自动改为 prerelease 撤出默认渠道。短暂网络错误也可能触发保守撤回；应检查原因后重新评审，不盲目重复上传。

公开后还需用旧版客户端检查新版本，用目标新版确认“已最新”；Android 真机验证原签名覆盖、账号与设置保留，Windows 完全退出托盘后验证覆盖。不能用单元测试代替这些设备操作。未完成的项目须明确记录在 `RELEASE_UPDATE_VERIFICATION.md`。

## 撤回与修复

发现严重问题时将该 Release 标记为 prerelease，并说明撤回原因。仅取消 Latest 不影响客户端按平台选择最高版本。修复二进制使用更高版本；Android 的 versionCode 高于所有已分发生产签名包，包括预览和已撤回包。不能复用旧标签覆盖文件。
