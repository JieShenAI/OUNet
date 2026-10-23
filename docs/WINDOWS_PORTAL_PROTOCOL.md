# Windows v0.2 门户只读协议核验

核验日期：2026-10-22。来源：[xha.ouc.edu.cn](https://xha.ouc.edu.cn/)、其 `a41.js` 与 `a43.js`，以及页面自身使用的只读配置/状态/在线记录响应。没有提交真实登录、注销、绑定或解绑操作。

| 来源 | 用途 |
| --- | --- |
| `/a79.htm` | 读取页面提供的客户端地址上下文 |
| `/a41.js` | 声明 HTTPS 端口、program、`drcom/chkstatus` 与页面配置 |
| `:端口/eportal/portal/page/loadConfig` | program/page_index 与字段标签配置 |
| `/drcom/chkstatus` | 当前会话 `uid`、源地址、MAC、是否在线 |
| `:端口/eportal/portal/page/loadOnlineRecord` | 页面自身查询的在线记录，参数与 a43.js 的 `editInfo.query_record` 对应 |

实际确认 `online_info_lang` 的映射如下；代码不根据本机网卡、应用启动时刻或登录参数推测这些值：

| 界面 | 来源 |
| --- | --- |
| 账号 | 当前在线状态 `uid` |
| 上线时间 | 匹配记录 `login_time` |
| 源 IP | `login_ip` |
| 源 IPv6 | `login_ip_v6` |
| 源 MAC | `mac_address` |
| 移动端标识 | `phone_flag`，页面定义 0=PC端，1=移动端 |

先匹配记录的 `is_owner_ip=1`，否则用当前状态的源 IP；多条匹配时进一步比较 MAC，仍不唯一则显示无法确认。核验时存在多条在线记录，当前设备标记唯一。只请求在线列表，不调用页面里的注销/解绑/绑定操作。

读取在线信息不发送保存的校园密码，账号使用站点已确认的当前在线 `uid`。请求域名固定，保留 TLS 验证，不跟随自动重定向，不执行远端 JavaScript。JSONP 只作为数据解析，完成结构校验后才结算请求成功。

测试保留使用文档地址和虚构账号的样本，真实原始响应仅存本机被忽略的 `.cache/`，不打包。只读实测报告仅保存字段存在性、接口状态、失败计数等摘要，不记录实际账号或地址。

真实登录提交沿用已支持的 `login_method=1/9` Portal 协议，并接入逐请求统计；本轮不为了验证主动提交真实密码。
