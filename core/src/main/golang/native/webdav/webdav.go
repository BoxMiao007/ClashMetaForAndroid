// Package webdav 提供绕开 Android Java HTTP 栈的 WebDAV 原始传输。
//
// 坚果云等网盘的 WAF 按 TLS 客户端指纹拦截 conscrypt(Java 栈)的连接,
// 而 Go 的 crypto/tls 已实测可正常通过。订阅下载本就由 Go 内核完成,
// 故云备份的 HTTP 传输统一收口到此处,由 Kotlin 侧经 JNI 桥调用。
package webdav

import (
	"io"
	"net/http"
	"os"
	"time"
)

// Request 发出一次 WebDAV HTTP 请求,把响应体(无论状态码)写入 outPath。
//
// authorization/userAgent 恒定发送;depth/contentType 为空串时不发送该头。
// bodyPath 为空串表示无请求体,否则从该文件读取(长度取文件大小,避免 chunked)。
// timeoutSeconds 为整个请求(连接到读完全部响应体)的超时。
// 重定向一律不跟随,3xx 原样返回,与原 HttpURLConnection
// instanceFollowRedirects=false 的语义一致(重定向会丢失 WebDAV 认证与动词语义)。
// 使用 net/http 默认 TLS 栈,不经任何指纹定制。
//
// 返回 HTTP 状态码;网络层失败(连接不上、超时、TLS 握手失败等)返回 0 与错误。
func Request(method, url, authorization, userAgent, depth, contentType, bodyPath, outPath string, timeoutSeconds int) (int, error) {
	var body io.ReadCloser
	var contentLength int64

	if bodyPath != "" {
		f, err := os.Open(bodyPath)
		if err != nil {
			return 0, err
		}
		defer f.Close()

		stat, err := f.Stat()
		if err != nil {
			return 0, err
		}

		body = f
		contentLength = stat.Size()
	}

	req, err := http.NewRequest(method, url, body)
	if err != nil {
		return 0, err
	}
	req.ContentLength = contentLength

	req.Header.Set("Authorization", authorization)
	req.Header.Set("User-Agent", userAgent)
	if depth != "" {
		req.Header.Set("Depth", depth)
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}

	client := &http.Client{
		Timeout: time.Duration(timeoutSeconds) * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()

	out, err := os.Create(outPath)
	if err != nil {
		return 0, err
	}

	_, err = io.Copy(out, resp.Body)
	if closeErr := out.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return 0, err
	}

	return resp.StatusCode, nil
}
