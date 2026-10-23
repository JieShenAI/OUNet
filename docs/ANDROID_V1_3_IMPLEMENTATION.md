# Android 1.3.0 实施与交付记录

> 本文保留1.3.0历史结果。其中校园识别和请求限流策略已由[1.3.1修复与验证](ANDROID_V1_3_1_FIX_VERIFICATION.md)替代；当前dist下通用更新元数据对应最新构建，请勿与旧APK混用。

日期：2026-10-23（Asia/Shanghai）。包名 `cn.jieshen.oucautologin`，版本1.3.0，versionCode 4，最低Android 9，targetSdk 35。对应[设计规划](ANDROID_V1_3_DESIGN_PLAN.md)。

## 已实现

| 规划 | 实现结果 |
| --- | --- |
| 只读Wi-Fi | 顶部仅显示实际连接名称；删除扫描、SSID输入、目标选择和系统切网流程，移除扫描/后台位置权限 |
| 校园识别优先 | 先绑定实际Wi-Fi，再通过HTTPS验证XHA根页；失败显示红色禁入图标和“非校园网”，不提交凭据 |
| 启动按需连接 | 已选账号即可启动本次认证，不要求目标SSID或后台监测开启；60秒节流、全账号共用三轮暂停保留 |
| 短节拍 | 默认1秒、最小0.3秒、最多三位小数，以整数毫秒保存；旧整数秒配置原子迁移，账号密文和失败保护保留 |
| 控制服务压力 | 网络事件合并、15秒稳定态心跳、Microsoft/Apple/Mozilla轮换；全体探测20次/滚动分钟，单运营方4次/分钟且间隔15秒；无请求积压补发 |
| XHA预算 | 所有业务共享60次/滚动分钟及单请求锁；普通详情最多使用52个额度，保留认证空间；认证期间暂停低优先级查询 |
| 在线身份 | 六字段来自服务器当前设备记录；学校详情失败不触发重登录；当前公开配置支持的状态读取模式为0 |
| 其他设备 | 原生二级页查询当前在线账号；20条分页、去重、账号变化清空、完整/未完整提示；不在100条截断后宣称完整 |
| 调用统计 | 恢复记录底部显示本进程XHA派发尝试总数与最近60秒滑动窗口；包含失败，排除缓存、未派发任务、公共探测及GitHub |
| 设置文档 | 随APK离线提供介绍、数据使用、JShen版权声明；各主页面固定免费学习交流说明 |
| GitHub更新 | 主动检查Android正式Release及元数据，校验包名、版本、固定来源、原证书指纹，打开浏览器下载，系统确认覆盖安装 |

只读校园识别采用成功缓存与失败退避。认证暂停不会继续登录或离线设备轮询；为了显示校园判定，仍可低频复核XHA根页，受同一预算约束。恢复计数必须有两次真实成功探测，间隔至少2秒，且满足探测预算；关联认证成功的证据最多保留60秒，超时后不冒称APP修复。

0.3秒是本地评估节拍，**不是每秒发送3.33次HTTP**。公共探测不会携带账号、密码、MAC、Cookie或门户Referer；XHA认证始终HTTPS。稳定态缓存评估不反复写磁盘检查时间，限流倒计时保存在进程内存。

## 协议核验范围

- 通过无凭据HTTPS读取XHA公开根页：HTTP 200，模板引用`a41.js`与`b80.css/b82.css`；识别规则同时支持此前登录页特征。
- 通过无凭据公开配置核对：`check_online_method=0`、`login_method=1`，在线列含上线时间、IPv4、IPv6、MAC与移动端标识。
- Mozilla当前探测端点返回预期`success`正文；原有Microsoft/Apple契约仍由本地回归测试覆盖。
- 以上操作没有登录、注销或查询个人在线记录。真实手机的UID、分页总数、MAC/IPv6空值、校园内实际认证仍需验收；其它未验证状态读取模式保守显示暂不可用。

## 自动验证与界面证据

构建脚本执行JUnit、Robolectric、Android Lint与release打包；最终计数、APK摘要和构建结果附在本文末尾。测试覆盖：

- 真实生产调用链在模拟Network上先XHA、后探测与登录；非校园网不发凭据；无需目标SSID或后台开关；移动数据默认路由不能掩盖Wi-Fi。
- 300ms节拍模拟10分钟，请求有界；单运营方间隔、总预算、并发计量、60秒窗口边界、认证保留额度和失败计数。
- 两个独立源失败、成功缓存、强制稳定复检、门户样式变化和XHA失败退避。
- 配置版本2→3、旧版迁移、密文保留、毫秒精度及认证节流不被切换/重启绕过。
- 设备列表超过100条、多页、空页、缺字段和账号不符；GitHub非Android标签、错误包名、证书、来源和哈希格式被拒绝。
- SQLite恢复事件去重与分页，Android 9/API28、Android 15/API35页面打开，320dp大字号下IPv6完整换行与固定免费说明。

下图由Robolectric运行本版真实布局生成，网络和身份值为合成测试数据，**不是手机实测截图**：

- [非校园网](images/android-v1.3/non-campus-api35.png)
- [在线六字段](images/android-v1.3/online-api35.png)
- [在线设备](images/android-v1.3/devices-api35.png)
- [恢复记录与调用卡](images/android-v1.3/recovery-api35.png)
- [设置](images/android-v1.3/settings-api35.png)
- [320dp、大字号与长IPv6](images/android-v1.3/online-narrow-large-api35.png)

## 尚未完成的真机验收

本机没有可用的Android真机/模拟器会话；Robolectric不是整机模拟器。以下未标为通过：

1. 小米/Redmi、iQOO、vivo、OPPO各自的SSID读取、双Wi-Fi/VPN、锁屏、后台、自启动和系统安装交互。
2. 校园网内真实账号认证、复通稳定性、学校接口实际分页及当前设备唯一匹配。
3. 真实设备1.0/1.1/1.2覆盖到1.3后的Keystore解密、监测恢复和数据保留。自动测试只证明迁移逻辑和合成密钥用例。
4. 发布后的GitHub在线检查、浏览器下载和覆盖安装。当前只准备本地Release附件，未创建远程Release。

## 安装与GitHub发布

试装包为 [ouc-autologin-1.3.0-release.apk](../android/dist/ouc-autologin-1.3.0-release.apk)，可校验[SHA-256文件](../android/dist/ouc-autologin-1.3.0-release.apk.sha256)。保持原正式签名；正常升级无需先卸载。

正式构建还生成：

- `android/dist/android-update.json`：实际APK摘要、版本、包名和签名指纹。
- `android/dist/ANDROID_RELEASE_NOTES.md`：发布说明。

用户完成真机验收后，在`JieShenAI/OUNet`创建正式标签`android-v1.3.0`的Release，上传**本次同一构建**的APK、`.sha256`、`android-update.json`和说明；源码提交应对应这批附件。不要使用Windows标签、debug APK或换一套签名。CI也会准备元数据并上传构建附件，但不会替用户自动创建Release。

旧版没有检查更新功能也能从发布页下载覆盖；1.3起可在设置内检查Android发布。未找到元数据或GitHub不可达时有明确提示，不会将Windows最新Release误认为Android更新，不影响校园认证。

## 最终构建结果

- 本地完整构建：通过；99项测试，0失败、0错误、0跳过；Android Lint 0错误、0警告。
- APK：173360字节；版本1.3.0 / versionCode 4。
- SHA-256：`b5dbbae590a60028bb9a1be615329944b859d752a67e224fe757fed10bef72d6`。
- 正式证书SHA-256：`6a5eedfcb02875007673e86772bc9da399e48e80f24dd88b85976126296fd91f`；元数据生成器已验证与原证书指纹一致。
- 主源码/资源/构建配置指纹：`34b2be82f64777ed89098b5eac35d57ab3b3001d111af881849bf3e6a1ca3239`。
- 产物与更新JSON的摘要一致，六张布局测试图已归档；尚未发布GitHub。

构建环境有JDK 21编译Java 8目标的弃用提示及未安装platform-tools的提示；不影响本次构建，但本次未执行ADB或真机测试。
