package com.github.kr328.clash.service.webdav

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
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
 * @param baseUrl WebDAV 服务器根地址,如 https://example.com/dav(可含路径,不以 / 结尾亦可)
 * @param remoteDirectory 备份目录名,默认与 verge 一致
 */
class WebDavClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
    remoteDirectory: String = DEFAULT_REMOTE_DIRECTORY,
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

    /** 确保远程目录存在(MKCOL)。目录已存在不算错误;409 表示父目录缺失。 */
    suspend fun ensureDirectory(): Unit = withContext(Dispatchers.IO) {
        open(directoryUrl, METHOD_MKCOL, TIMEOUT_FAST_MS).useConnection { connection ->
            val code = connection.responseCode
            when {
                code in HTTP_SUCCESS -> Unit
                // RFC 4918:MKCOL 到已存在的目录返回 405
                code == HTTP_METHOD_NOT_ALLOWED -> Unit
                code == HTTP_CONFLICT -> throw WebDavException(
                    "创建远程目录失败: 父目录缺失(HTTP 409)",
                    code,
                )
                else -> {
                    // 部分服务器不按 RFC 返回 405,而是 4xx/5xx 附带 "already exist" 文本
                    val body = readBody(connection)
                    if (!body.contains(BODY_ALREADY_EXISTS, ignoreCase = true)) {
                        throw WebDavException("创建远程目录失败: HTTP $code $body", code)
                    }
                }
            }
        }
    }

    /** 列出云端全部备份包,按时间倒序(见 [CloudBackup.effectiveTimeMillis])。 */
    suspend fun listBackups(): List<CloudBackup> = withContext(Dispatchers.IO) {
        val files = open(directoryUrl, METHOD_PROPFIND, TIMEOUT_FAST_MS).useConnection { connection ->
            connection.setRequestProperty(HEADER_DEPTH, "1")
            connection.setRequestProperty(HEADER_CONTENT_TYPE, CONTENT_TYPE_XML)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(PROPFIND_BODY.size)

            connection.outputStream.use { it.write(PROPFIND_BODY) }

            val code = connection.responseCode
            if (code != HTTP_MULTI_STATUS) {
                throw WebDavException("获取云端历史失败: HTTP $code ${readBody(connection)}", code)
            }
            connection.inputStream.use(::parseMultistatus)
        }
        files.sortedWith(compareByDescending<CloudBackup> { it.effectiveTimeMillis ?: Long.MIN_VALUE })
    }

    /** 下载备份包(GET)。文件不存在时抛出带 404 状态码的 [WebDavException]。 */
    suspend fun fetchBackup(name: String): ByteArray = withContext(Dispatchers.IO) {
        val url = backupUrl(name)
        open(url, METHOD_GET, TIMEOUT_TRANSFER_MS).useConnection { connection ->
            val code = connection.responseCode
            if (code != HTTP_OK) {
                throw WebDavException("下载备份包失败: HTTP $code ${readBody(connection)}", code)
            }
            connection.inputStream.use { it.readBytes() }
        }
    }

    /** 上传备份包(PUT)。失败自动重试 1 次,间隔 500 毫秒;4xx 错误不重试。 */
    suspend fun uploadBackup(name: String, data: ByteArray): Unit = withContext(Dispatchers.IO) {
        val url = backupUrl(name)
        var attempt = 0
        while (true) {
            try {
                open(url, METHOD_PUT, TIMEOUT_TRANSFER_MS).useConnection { connection ->
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(data.size)
                    connection.outputStream.use { it.write(data) }

                    val code = connection.responseCode
                    if (code !in HTTP_SUCCESS) {
                        throw WebDavException("上传备份包失败: HTTP $code ${readBody(connection)}", code)
                    }
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
        open(url, METHOD_DELETE, TIMEOUT_FAST_MS).useConnection { connection ->
            val code = connection.responseCode
            if (code !in HTTP_SUCCESS) {
                throw WebDavException("删除备份包失败: HTTP $code ${readBody(connection)}", code)
            }
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

    private fun open(url: String, method: String, timeoutMillis: Int): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        // WebDAV 请求携带认证与方法语义,重定向会导致语义丢失,直接失败以便定位
        connection.instanceFollowRedirects = false
        connection.setRequestProperty(HEADER_AUTHORIZATION, authorization)
        connection.setRequestProperty(HEADER_USER_AGENT, USER_AGENT)
        return connection
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
                                        fileTimeMillis = CloudBackup.parseFileNameTime(name),
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

    /**
     * 读取错误响应体前 200 字符用于诊断。读取失败不影响主错误——
     * HTTP 状态码已在异常消息中。
     */
    private fun readBody(connection: HttpURLConnection): String =
        try {
            val stream = connection.errorStream ?: connection.inputStream
            stream?.readBytes()?.toString(Charsets.UTF_8)?.take(200) ?: ""
        } catch (e: IOException) {
            ""
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
        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val HEADER_USER_AGENT = "User-Agent"
        private const val HEADER_DEPTH = "Depth"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val CONTENT_TYPE_XML = "application/xml; charset=utf-8"
        private const val BODY_ALREADY_EXISTS = "already exist"

        private const val HTTP_OK = HttpURLConnection.HTTP_OK
        private const val HTTP_METHOD_NOT_ALLOWED = HttpURLConnection.HTTP_BAD_METHOD
        private const val HTTP_CONFLICT = HttpURLConnection.HTTP_CONFLICT

        // HttpURLConnection 无 207 常量
        private const val HTTP_MULTI_STATUS = 207
        private val HTTP_SUCCESS = 200..299

        /** 列表/删除类操作超时(与 verge 一致) */
        private const val TIMEOUT_FAST_MS = 30_000

        /** 上传/下载超时(与 verge 一致) */
        private const val TIMEOUT_TRANSFER_MS = 300_000
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

/** 执行后断开连接。备份操作低频,不值得为保活引入连接池心智负担。 */
private inline fun <T : HttpURLConnection, R> T.useConnection(block: (T) -> R): R =
    try {
        block(this)
    } finally {
        disconnect()
    }
