# OUNet Windows 0.3.5

Windows 10 1809+ / Windows 11 x64。

- 校园账号卡片默认显示上一次读取的账号、上线时间、移动端标识、IPv4、IPv6和MAC，无需展开历史。
- 取消“校园身份尚未读取”和各字段“尚未读取”提示；首次使用尚无记录时显示简洁占位，可主动刷新。
- 网络变化、XHA未登录或刷新失败时保留旧信息及原读取时间；成功读取新的账号信息后再替换。上次信息仍标注为上次读取，避免与当前登录状态混淆。
- 包含自动复检、账号输入不中断和按钮闪烁修复。

安装包：`OUNet-0.3.5-Setup-x64.exe`；完整免安装包：`OUNet-0.3.5-Windows-x64-portable.zip`。升级前从托盘完全退出旧版，沿用原AppId、账号加密和数据目录。

本版本未做Windows代码签名，提供SHA-256校验文件。[验证记录](https://github.com/JieShenAI/OUNet/blob/main/docs/WINDOWS_V0_3_5_VERIFICATION.md)。
