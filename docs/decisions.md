# 技术决策记录

- 2026-11-09:同步方向做成五个并列模式(双向同步 / 云端→本机覆盖 / 云端→本机只增 / 本机→云端覆盖 / 本机→云端只增),判定集中在同步计划器,变更确认复用既有两段式流程,来源端为空时拒绝执行。为什么:单一的双向并集合并无法满足换机还原、把本机推上去覆盖桌面、只补增量互不影响、防另一端误改这四类诉求(见 #8)。放弃了:把方向写进云端包让 verge 感知(破坏零改动互通前提)、按单条订阅分设方向与 dry-run 预览(YAGNI)。
- 2026-10-02:WebDAV 的 HTTP 传输迁入 Go 内核(详见 `docs/adr/0002-webdav-over-go-bridge.md`)。为什么:坚果云 WAF 按 TLS 指纹拦截 Android Java 栈的 WebDAV 请求(501),实测 Go 栈可通过。放弃了:OkHttp(同 TLS 栈无效)、BouncyCastle JSSE(未验证的猜测)。
- 2026-10-01:WebDAV 订阅同步直接读写 clash-verge-rev 原生备份包格式(详见 `docs/adr/0001-webdav-interop-with-clash-verge-rev.md`)。为什么:verge 是官方应用不可改动,读写其原生格式是零改动互通的唯一通路。放弃了:自定义中立同步格式(verge 识别不了)、修改 clash-verge-rev(违背官方端不动)。
