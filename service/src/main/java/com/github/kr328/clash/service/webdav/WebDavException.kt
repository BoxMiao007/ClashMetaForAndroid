package com.github.kr328.clash.service.webdav

/**
 * WebDAV 操作失败。
 *
 * [statusCode] 为服务器返回的 HTTP 状态码;网络层失败(连接不上、超时、读写中断等)
 * 时为 null,此时 [cause] 保留原始异常,便于定位原因。
 */
class WebDavException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /**
     * 上传重试判定:网络层失败(无状态码)或服务器端错误(5xx)可重试;
     * 认证失败、路径不存在等 4xx 错误重试无意义,直接失败。
     */
    internal val retryable: Boolean
        get() = statusCode == null || statusCode >= 500
}
