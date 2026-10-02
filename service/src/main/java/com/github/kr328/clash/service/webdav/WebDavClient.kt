package com.github.kr328.clash.service.webdav

import android.util.Base64
import com.github.kr328.clash.common.sync.BackupFileName
import com.github.kr328.clash.core.bridge.Bridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/**
 * clash-verge-rev 兼容的 WebDAV 云端备份历史薄接口(Basic 认证)。
 *
 * 行为基准(clash-verge-rev backup.rs):
 * - 固定目录 [DEFAULT_REMOTE_DIRECTORY];
 * - 列表/删除 30 秒、上传/下载 300 秒超时;上传失败自动重试 1 次,间隔 500 毫秒;
 * - MKCOL 405 或响应体含 "already exist" 视为目录已存在,409 视为父目录缺失;
 * - 列表按 [CloudBackup.effectiveTimeMillis] 时间倒序。
 *
 * 失败统一以 [WebDavException](带 HTTP 状态码)或原始 IOException 抛出,不吞异常。
 * 调用方在 [listBackups] 前需先 [ensureDirectory]。
 * 与 verge 不同,本实现不做证书豁免,证书问题原样暴露,便于定位。
 *
 * HTTP 传输走 Go 内核([Bridge.nativeWebdavRequest],net/http + crypto/tls):
 * 坚果云等网盘的 WAF 按 TLS 客户端指纹拦截 Android Java 栈(conscrypt)的连接,
 * Go 的 TLS 栈可正常通过,且与订阅下载同栈。
 *
 * @param baseUrl WebDAV 服务器根地址,如 https://example.com/dav(可含路径,不以 / 结尾亦可)
 * @param cacheDir 用于存放请求/响应临时文件的目录(大文件走文件而非 JNI 内存数组)
 * @param remoteDirectory 备份目录名,默认与 verge 一致
 */
class WebDavClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val cacheDir: File,
    private val remoteDirectory: String = DEFAULT_REMOTE_DIRECTORY,
) {
    private val directoryUrl = "${baseUrl.trimEnd('/')}/$remoteDirectory/"

    private val authorization = "Basic " + Base64.encodeToString(
        "$username:$password".toByteArray(Charsets.UTF_8),
        Base64.NO_WRAP,
    )

    init {
        require(
            remoteDirectory.isNotEmpty() &&
                remoteDirectory != "." && remoteDirectory != ".." &&
                remoteDirectory.none { it == '/' || it.isWhitespace() },
        ) { "非法的远程目录名: $remoteDirectory" }

        try {
            URL(directoryUrl)
        } catch (e: MalformedURLException) {
            throw IllegalArgumentException("非法的 WebDAV 服务器地址: $baseUrl", e)
        }
    }

    /**
     * 确保远程目录存在(MKCOL)。目录已存在不算错误;409 表示父目录缺失。
     * 403 不中断(如坚果云禁止 WebDAV 建目录,目录可能已由其客户端创建),
     * 目录若真缺失,后续 PROPFIND/PUT 会给出带指引的明确错误。
     */
    suspend fun ensureDirectory(): Unit = withContext(Dispatchers.IO) {
        val (code, body) = request(directoryUrl, METHOD_MKCOL, TIMEOUT_FAST_SECONDS)
        when {
            code in HTTP_SUCCESS -> Unit
            // RFC 4918:MKCOL 到已存在的目录返回 405
            code == HTTP_METHOD_NOT_ALLOWED -> Unit
            code == HTTP_FORBIDDEN -> Unit
            code == HTTP_CONFLICT -> throw WebDavException(
                "创建远程目录失败: 父目录缺失(HTTP 409)",
                code,
            )
            else -> {
                // 部分服务器不按 RFC 返回 405,而是 4xx/5xx 附带 "already exist" 文本
                if (!body.contains(BODY_ALREADY_EXISTS, ignoreCase = true)) {
                    throw WebDavException("创建远程目录失败: HTTP $code $body", code)
                }
            }
        }
    }

    /** 列出云端全部备份包,按时间倒序(见 [CloudBackup.effectiveTimeMillis])。 */
    suspend fun listBackups(): List<CloudBackup> = withContext(Dispatchers.IO) {
        val (code, body) = request(
            directoryUrl,
            METHOD_PROPFIND,
            TIMEOUT_FAST_SECONDS,
            depth = "1",
            contentType = CONTENT_TYPE_XML,
            requestBody = PROPFIND_BODY,
        )
        if (code != HTTP_MULTI_STATUS) {
            // 部分网盘(如坚果云)目录缺失时不返回 404,而是 403/501 等不支持类错误
            if (code == 404 || code == 501) {
                throw WebDavException(
                    "云端目录 $remoteDirectory 不存在或服务器不支持列出:" +
                        "请先在网盘的网页/客户端里手动创建该目录后重试",
                    code,
                )
            }
            throw WebDavException("获取云端历史失败: HTTP $code $body", code)
        }
        body.byteInputStream().use(::parseMultistatus)
            .sortedWith(compareByDescending<CloudBackup> { it.effectiveTimeMillis ?: Long.MIN_VALUE })
    }

    /** 下载备份包(GET)。文件不存在时抛出带 404 状态码的 [WebDavException]。 */
    suspend fun fetchBackup(name: String): ByteArray = withContext(Dispatchers.IO) {
        val url = backupUrl(name)
        val (code, data, body) = requestForBytes(url, METHOD_GET, TIMEOUT_TRANSFER_SECONDS)
        if (code != HTTP_OK) {
            throw WebDavException("下载备份包失败: HTTP $code $body", code)
        }
        data
    }

    /** 上传备份包(PUT)。失败自动重试 1 次,间隔 500 毫秒;4xx 错误不重试。 */
    suspend fun uploadBackup(name: String, data: ByteArray): Unit = withContext(Dispatchers.IO) {
        val url = backupUrl(name)
        var attempt = 0
        while (true) {
            try {
                val (code, _, body) = requestForBytes(
                    url,
                    METHOD_PUT,
                    TIMEOUT_TRANSFER_SECONDS,
                    requestBody = data,
                )
                if (code !in HTTP_SUCCESS) {
                    throw WebDavException("上传备份包失败: HTTP $code $body", code)
                }
                return@withContext
            } catch (e: WebDavException) {
                if (!e.retryable || attempt >= MAX_UPLOAD_RETRIES) throw e
            } catch (e: IOException) {
                if (attempt >= MAX_UPLOAD_RETRIES) {
                    throw WebDavException("上传备份包失败: ${e.message}", cause = e)
                }
            }
            attempt++
            delay(RETRY_DELAY_MS)
        }
    }

    /** 删除备份包(DELETE)。 */
    suspend fun deleteBackup(name: String): Unit = withContext(Dispatchers.IO) {
        val url = backupUrl(name)
        val (code, body) = request(url, METHOD_DELETE, TIMEOUT_FAST_SECONDS)
        if (code !in HTTP_SUCCESS) {
            throw WebDavException("删除备份包失败: HTTP $code $body", code)
        }
    }

    /** 校验备份包文件名并拼出完整 URL。文件名由本应用生成,禁止路径分隔与空白。 */
    private fun backupUrl(name: String): String {
        require(
            name.isNotEmpty() && name != "." && name != ".." &&
                name.none { it == '/' || it.isWhitespace() },
        ) { "非法的备份包文件名: $name" }
        return "$directoryUrl$name"
    }

    /**
     * 发出一次请求,返回 (状态码, 响应体文本)。响应体按 UTF-8 解码,仅用于
     * 207 multistatus 与错误诊断,别用于二进制。
     * 网络层失败(连接不上、超时、TLS 握手失败)抛原始 [IOException]。
     */
    private fun request(
        url: String,
        method: String,
        timeoutSeconds: Int,
        depth: String? = null,
        contentType: String? = null,
        requestBody: ByteArray? = null,
    ): Pair<Int, String> {
        val (code, data, body) = requestForBytes(url, method, timeoutSeconds, depth, contentType, requestBody)
        return code to body
    }

    /**
     * 发出一次请求,返回 (状态码, 响应体字节, 响应体文本)。文本仅错误时才解码使用。
     * 经 [Bridge.nativeWebdavRequest] 由 Go 内核传输;请求/响应体经临时文件
     * 中转(备份 zip 可达数 MB,不走 JNI 内存数组),finally 里清理。
     * 状态码 0 表示网络层失败,Go 侧已把错误文本写入响应临时文件。
     */
    private fun requestForBytes(
        url: String,
        method: String,
        timeoutSeconds: Int,
        depth: String? = null,
        contentType: String? = null,
        requestBody: ByteArray? = null,
    ): Triple<Int, ByteArray, String> {
        val outFile = File.createTempFile("cfa-webdav-out", null, cacheDir)
        val inFile = requestBody?.let {
            File.createTempFile("cfa-webdav-in", null, cacheDir).apply { writeBytes(it) }
        }
        try {
            val code = Bridge.nativeWebdavRequest(
                method,
                url,
                authorization,
                USER_AGENT,
                depth.orEmpty(),
                contentType.orEmpty(),
                inFile?.absolutePath.orEmpty(),
                outFile.absolutePath,
                timeoutSeconds,
            )
            val data = outFile.readBytes()
            if (code == 0) {
                // Go 侧网络错误文本在此文件里;空则给兜底消息
                throw IOException(data.toString(Charsets.UTF_8).ifEmpty { "网络请求失败" })
            }
            return Triple(code, data, data.toString(Charsets.UTF_8))
        } finally {
            inFile?.delete()
            outFile.delete()
        }
    }

    /**
     * 解析 PROPFIND 的 207 multistatus 响应,提取文件名与 last_modified。
     * 目录条目(resourcetype 含 collection 或 href 以 / 结尾)剔除。
     */
    private fun parseMultistatus(stream: InputStream): List<CloudBackup> =
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            // 开启命名空间解析后 parser.name 即 localName,兼容 D:/d:/无前缀等服务器差异
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(stream, null)

            val result = mutableListOf<CloudBackup>()
            var href: String? = null
            var lastModified: String? = null
            var isCollection = false
            var textTarget: String? = null
            val textBuffer = StringBuilder()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        ELEMENT_RESPONSE -> {
                            href = null
                            lastModified = null
                            isCollection = false
                        }
                        ELEMENT_HREF, ELEMENT_LAST_MODIFIED -> {
                            textTarget = parser.name
                            textBuffer.setLength(0)
                        }
                        ELEMENT_COLLECTION -> isCollection = true
                    }
                    // 长文本可能被拆成多个事件,需累加
                    XmlPullParser.TEXT, XmlPullParser.CDSECT ->
                        if (textTarget != null) textBuffer.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        ELEMENT_HREF -> {
                            href = textBuffer.toString()
                            textTarget = null
                        }
                        ELEMENT_LAST_MODIFIED -> {
                            lastModified = textBuffer.toString()
                            textTarget = null
                        }
                        ELEMENT_RESPONSE -> {
                            val raw = href
                            if (raw != null && !isCollection && !raw.endsWith("/")) {
                                fileNameFromHref(raw)?.let { name ->
                                    result += CloudBackup(
                                        name = name,
                                        fileTimeMillis = BackupFileName.parseTimestampSeconds(name)
                                            ?.times(1000L),
                                        lastModifiedMillis = lastModified?.let {
                                            CloudBackup.parseServerDate(it)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
            result
        } catch (e: XmlPullParserException) {
            throw WebDavException("解析 WebDAV 207 multistatus 响应失败: ${e.message}", cause = e)
        }

    /**
     * 从 href 提取文件名。href 可能是完整 URL 或相对路径,且经百分号编码;
     * 用 URI 解析可同时得到解码后的路径。服务器返回不合规 href 时回退原始串。
     */
    private fun fileNameFromHref(href: String): String? {
        val path = try {
            URI(href).path ?: href
        } catch (e: URISyntaxException) {
            href
        }
        val name = path.trimEnd('/').substringAfterLast('/')
        return name.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** verge 的固定备份目录,与其保持一致以共用云端历史 */
        const val DEFAULT_REMOTE_DIRECTORY = "clash-verge-rev-backup"

        private const val USER_AGENT = "ClashMetaForAndroid WebDAV-Client"
        private const val METHOD_MKCOL = "MKCOL"
        private const val METHOD_PROPFIND = "PROPFIND"
        private const val METHOD_GET = "GET"
        private const val METHOD_PUT = "PUT"
        private const val METHOD_DELETE = "DELETE"
        private const val CONTENT_TYPE_XML = "application/xml; charset=utf-8"
        private const val BODY_ALREADY_EXISTS = "already exist"

        private const val HTTP_OK = 200
        private const val HTTP_MULTI_STATUS = 207
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_CONFLICT = 409
        private val HTTP_SUCCESS = 200..299

        /** 列表/删除类操作超时秒数(与 verge 一致) */
        private const val TIMEOUT_FAST_SECONDS = 30

        /** 上传/下载超时秒数(与 verge 一致) */
        private const val TIMEOUT_TRANSFER_SECONDS = 300
        private const val MAX_UPLOAD_RETRIES = 1
        private const val RETRY_DELAY_MS = 500L

        // PROPFIND 请求体只声明本实现用到的属性
        private val PROPFIND_BODY =
            ("""<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:">""" +
                """<d:prop><d:resourcetype/><d:getlastmodified/></d:prop></d:propfind>""")
                .toByteArray(Charsets.UTF_8)

        private const val ELEMENT_RESPONSE = "response"
        private const val ELEMENT_HREF = "href"
        private const val ELEMENT_LAST_MODIFIED = "getlastmodified"
        private const val ELEMENT_COLLECTION = "collection"
    }
}
