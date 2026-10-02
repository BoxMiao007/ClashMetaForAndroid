---
status: accepted
---

# 0002 — WebDAV 的 HTTP 传输走 Go 内核而非 Kotlin

坚果云的防护会按 TLS/客户端指纹拦截 WebDAV 请求:同一部手机同一网络,系统 curl(PROPFIND)= 207、PC 的 curl 与 Go 均为 207,唯独 App 的 Java 栈(Android HttpURLConnection/conscrypt)= 501,且请求行已被本地服务器证实完全正确。因此把 WebDAV 的 HTTP 传输迁入 Go 内核(net/http,经既有 JNI 桥模式导出 `webdavRequest`),Kotlin 侧 `WebDavClient` 对外 API 不变,请求/响应体经 cacheDir 临时文件中转以支持大包。

## Considered Options

- OkHttp:TLS 栈仍是 conscrypt,指纹不变,无法解决,放弃。
- BouncyCastle JSSE 换指纹:未经验证的猜测,依赖重,放弃。
- 保持 HttpURLConnection + 用户侧换网绕开:把环境问题留给用户,不符合「功能开箱可用」,放弃。

## Consequences

- WebDAV 可用性从此绑定 Go 内核的构建与发布;桥接胶水(Go/C/JNI)无单元测试,行为依赖真机验证。
- 对其他按指纹拦 WAF 的网盘,本方案同样免疫(传输指纹为 Go)。
