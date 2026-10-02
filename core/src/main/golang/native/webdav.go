package main

//#include "bridge.h"
import "C"

import (
	"os"

	"cfa/native/webdav"
)

//export webdavRequest
func webdavRequest(method, url, authorization, userAgent, depth, contentType, bodyPath, outPath C.c_string, timeoutSeconds C.int) C.int {
	code, err := webdav.Request(
		C.GoString(method),
		C.GoString(url),
		C.GoString(authorization),
		C.GoString(userAgent),
		C.GoString(depth),
		C.GoString(contentType),
		C.GoString(bodyPath),
		C.GoString(outPath),
		int(timeoutSeconds),
	)

	if err != nil {
		// 网络层失败:状态码 0,错误文本写入 outPath 供 Kotlin 侧构造异常消息。
		// 写入失败只能吞掉——Kotlin 侧会得到状态码 0 与空消息,仍能按网络错误处理。
		_ = os.WriteFile(C.GoString(outPath), []byte(err.Error()), 0o600)

		return 0
	}

	return C.int(code)
}
