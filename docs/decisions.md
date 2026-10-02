# 技术决策记录

- 2026-10-02:WebDAV 的 HTTP 传输迁入 Go 内核(详见 `docs/adr/0002-webdav-over-go-bridge.md`)。为什么:坚果云 WAF 按 TLS 指纹拦截 Android Java 栈的 WebDAV 请求(501),实测 Go 栈可通过。放弃了:OkHttp(同 TLS 栈无效)、BouncyCastle JSSE(未验证的猜测)。
- 2026-10-01:WebDAV 订阅同步直接读写 clash-verge-rev 原生备份包格式(详见 `docs/adr/0001-webdav-interop-with-clash-verge-rev.md`)。为什么:verge 是官方应用不可改动,读写其原生格式是零改动互通的唯一通路。放弃了:自定义中立同步格式(verge 识别不了)、修改 clash-verge-rev(违背官方端不动)。
