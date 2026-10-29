package com.kakario.comicviewer.ftp

import com.kakario.comicviewer.data.FtpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration

/** 화면에 보여줄 파일/폴더 하나. */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
) {
    val isComicArchive: Boolean
        get() = !isDirectory && name.substringAfterLast('.', "").lowercase() in ARCHIVE_EXT

    private companion object {
        val ARCHIVE_EXT = setOf("zip", "cbz")
    }
}

/** 사용자에게 그대로 보여줄 한국어 오류. */
class FtpUserException(message: String) : Exception(message)

/**
 * 서버 하나에 대한 FTP 연결. 한 번 접속하면 연결을 유지하고,
 * 끊겼으면 다음 요청 때 자동으로 다시 접속한다.
 */
class FtpBrowser(private val server: FtpServer) {
    private val client = FTPClient()
    private val mutex = Mutex()

    /** 접속 직후 서버가 알려준 시작 폴더. */
    var homePath: String = "/"
        private set

    suspend fun connect(): String = ftp {
        ensureConnected()
        homePath
    }

    suspend fun list(path: String): List<RemoteEntry> = ftp {
        ensureConnected()
        if (!client.changeWorkingDirectory(path)) {
            throw FtpUserException("폴더를 열 수 없어요: $path")
        }
        val files = client.listFiles()
        if (!FTPReply.isPositiveCompletion(client.replyCode) && files.isEmpty()) {
            throw FtpUserException(
                "목록을 받지 못했어요 (응답 ${client.replyCode}).\n" +
                    "외부 접속이라면 공유기에서 FTP 패시브 포트도 포트포워딩돼 있는지 확인해 주세요."
            )
        }
        files.filterNotNull()
            .filter { it.name != "." && it.name != ".." && !it.name.startsWith(".") }
            .map { f ->
                RemoteEntry(
                    name = f.name,
                    path = joinPath(path, f.name),
                    isDirectory = f.isDirectory,
                    size = f.size,
                )
            }
            .sortedWith(
                compareByDescending<RemoteEntry> { it.isDirectory }
                    .thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) }
            )
    }

    suspend fun close() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    if (client.isConnected) {
                        client.logout()
                        client.disconnect()
                    }
                } catch (_: IOException) {
                }
            }
        }
    }

    /** 모든 FTP 작업을 IO 스레드에서, 한 번에 하나씩 실행하고 오류를 한국어로 바꾼다. */
    private suspend fun <T> ftp(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                block()
            } catch (e: FtpUserException) {
                throw e
            } catch (e: UnknownHostException) {
                throw FtpUserException("주소를 찾을 수 없어요. 서버 주소를 다시 확인해 주세요.")
            } catch (e: SocketTimeoutException) {
                dropConnection()
                throw FtpUserException(
                    "서버 응답이 없어요.\n외부 접속이라면 공유기 포트포워딩(${server.port}번)과 서버가 켜져 있는지 확인해 주세요."
                )
            } catch (e: ConnectException) {
                dropConnection()
                throw FtpUserException(
                    "서버에 연결할 수 없어요.\n주소·포트가 맞는지, 외부 접속이라면 포트포워딩이 돼 있는지 확인해 주세요."
                )
            } catch (e: NoRouteToHostException) {
                dropConnection()
                throw FtpUserException("서버까지 가는 경로가 없어요. 인터넷 연결과 주소를 확인해 주세요.")
            } catch (e: IOException) {
                dropConnection()
                throw FtpUserException("연결 중 문제가 생겼어요: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun ensureConnected() {
        if (client.isConnected) {
            val alive = try {
                client.sendNoOp()
            } catch (_: IOException) {
                false
            }
            if (alive) return
            dropConnection()
        }

        // 한글 파일명 인코딩은 접속 전에 정해야 한다.
        client.controlEncoding = server.encoding
        client.connectTimeout = 10_000
        client.setDefaultTimeout(15_000)
        client.connect(server.host, server.port)

        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            val code = client.replyCode
            dropConnection()
            throw FtpUserException("서버가 접속을 거부했어요 (응답 $code).")
        }
        client.soTimeout = 20_000
        client.setDataTimeout(Duration.ofSeconds(20))

        val user = server.user.ifBlank { "anonymous" }
        val pass = if (server.user.isBlank()) "guest@" else server.password
        if (!client.login(user, pass)) {
            dropConnection()
            throw FtpUserException("로그인에 실패했어요. 아이디와 비밀번호를 확인해 주세요.")
        }
        if (server.encoding.equals("UTF-8", ignoreCase = true)) {
            // 지원하지 않는 서버는 그냥 무시하므로 보내도 안전하다.
            try {
                client.sendCommand("OPTS UTF8 ON")
            } catch (_: IOException) {
            }
        }
        client.setFileType(FTP.BINARY_FILE_TYPE)
        // 외부(공유기 너머)에서 접속할 때는 패시브 모드가 필요하다.
        // commons-net은 서버가 내부 IP(192.168.x.x)를 알려줘도 접속한 주소로 바꿔서 연결해준다.
        client.enterLocalPassiveMode()
        client.isRemoteVerificationEnabled = false

        homePath = client.printWorkingDirectory()?.takeIf { it.isNotBlank() } ?: "/"
    }

    private fun dropConnection() {
        try {
            if (client.isConnected) client.disconnect()
        } catch (_: IOException) {
        }
    }

    companion object {
        fun joinPath(parent: String, name: String): String =
            if (parent.endsWith("/")) parent + name else "$parent/$name"

        fun parentPath(path: String): String {
            val trimmed = path.trimEnd('/')
            val idx = trimmed.lastIndexOf('/')
            return if (idx <= 0) "/" else trimmed.substring(0, idx)
        }
    }
}

/** "2권"이 "10권"보다 앞에 오도록 숫자를 숫자로 비교하는 정렬. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val ca = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val cb = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val r = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else {
                x.compareTo(y)
            }
            if (r != 0) return r
        }
        return ca.size - cb.size
    }
}
