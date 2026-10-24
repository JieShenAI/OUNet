# OUNet Windows 0.3.2

Windows 10 1809+ / Windows 11 x64。

- 修复后台检测时各页面按钮反复变灰、恢复的闪烁。
- 主连接按钮不再随自动检测来回切换文字，仅在用户主动重连时显示处理进度。
- 账号列表和选择框在内容不变时保留控件，避免重复创建引起的闪烁与焦点丢失。
- 忙碌时继续拦截重复操作，保留键盘 Tab 导航、真实不可用状态和请求保护。

## 安装与升级

安装版下载 `OUNet-0.3.2-Setup-x64.exe`；免安装版下载 `OUNet-0.3.2-Windows-x64-portable.zip` 并保留完整目录。升级前从托盘完全退出旧版，再运行安装器覆盖安装。

沿用原 AppId、账号加密与数据目录。本版本未做 Windows 代码签名，提供同名 SHA-256 校验文件。自动化与待完成的实机验收范围见 [验证记录](https://github.com/JieShenAI/OUNet/blob/main/docs/WINDOWS_V0_3_2_VERIFICATION.md)。

作者邮箱：[shencv@163.com](mailto:shencv@163.com) · [开源项目](https://github.com/JieShenAI/OUNet) · [GitHub 下载页](https://github.com/JieShenAI/OUNet/releases)。
