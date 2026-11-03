package com.kakario.comicviewer.ftp

import com.kakario.comicviewer.data.FtpServer
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.model.NaturalOrder
import com.kakario.comicviewer.model.RangeSource
import com.kakario.comicviewer.model.UserFacingException
import com.kakario.comicviewer.model.joinPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration

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

    suspend fun list(path: String): List<FileEntry> = ftp {
        ensureConnected()
        if (!client.changeWorkingDirectory(path)) {
            throw UserFacingException("폴더를 열 수 없어요: $path")
        }
        val files = client.listFiles()
        if (!FTPReply.isPositiveCompletion(client.replyCode) && files.isEmpty()) {
            throw UserFacingException(
                "목록을 받지 못했어요 (응답 ${client.replyCode}).\n" +
                    "외부 접속이라면 공유기에서 FTP 패시브 포트도 포트포워딩돼 있는지 확인해 주세요."
            )
        }
        files.filterNotNull()
            .filter { it.name != "." && it.name != ".." && !it.name.startsWith(".") }
            .map { f ->
                FileEntry(
                    name = f.name,
                    path = joinPath(path, f.name),
                    isDirectory = f.isDirectory,
                    size = f.size,
                    modified = f.timestamp?.timeInMillis ?: 0,
                )
            }
            .sortedWith(
                compareByDescending<FileEntry> { it.isDirectory }
                    .thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) }
            )
    }

    /**
     * 파일 하나를 RangeSource로 열어 block을 실행한다 (연결을 잡고 있는 동안만 유효).
     * ZIP 전체를 받지 않고 필요한 부분(목차, 첫 이미지)만 읽을 때 쓴다.
     */
    suspend fun <T> useFile(path: String, fileSize: Long, block: (RangeSource) -> T): T = ftp {
        ensureConnected()
        block(object : RangeSource {
            override val size: Long = fileSize
            override fun read(offset: Long, length: Int): ByteArray = readRangeLocked(path, offset, length)
        })
    }

    /** FTP 이어받기(REST)로 offset부터 length바이트만 받는다. 잠금을 잡은 상태에서만 호출. */
    private fun readRangeLocked(path: String, offset: Long, length: Int): ByteArray {
        ensureConnected()
        client.setRestartOffset(offset)
        val input = client.retrieveFileStream(path)
            ?: throw UserFacingException("파일을 열 수 없어요 (응답 ${client.replyCode})")
        val buf = ByteArray(length)
        var read = 0
        try {
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
        } finally {
            try {
                input.close()
            } catch (_: IOException) {
            }
        }
        // 중간에 끊으면 서버가 426/226 같은 응답을 보낸다. 받아서 정리하고,
        // NOOP 응답이 정확히 200이 아니면 응답 순서가 꼬인 것이므로 다시 접속한다.
        try {
            client.completePendingCommand()
            if (client.noop() != 200) dropConnection()
        } catch (_: IOException) {
            dropConnection()
        }
        client.setRestartOffset(0)
        return if (read < length) buf.copyOf(read) else buf
    }

    /** 작은 파일 하나를 통째로 읽는다 (없으면 null). 중복 검사 기록 파일용. */
    suspend fun readSmall(path: String): ByteArray? = ftp {
        ensureConnected()
        client.setRestartOffset(0)
        val out = ByteArrayOutputStream()
        if (client.retrieveFile(path, out)) out.toByteArray() else null
    }

    /** 작은 파일 하나를 쓴다 (덮어쓰기). 쓰기 권한이 없으면 false. */
    suspend fun writeSmall(path: String, data: ByteArray): Boolean = ftp {
        ensureConnected()
        client.setRestartOffset(0)
        client.storeFile(path, ByteArrayInputStream(data))
    }

    /** 서버에서 파일 하나를 지운다. */
    suspend fun delete(path: String) = ftp {
        ensureConnected()
        if (!client.deleteFile(path)) {
            throw UserFacingException(
                "삭제하지 못했어요 (응답 ${client.replyCode}).\nFTP 계정에 지우기 권한이 있는지 확인해 주세요."
            )
        }
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
            } catch (e: UserFacingException) {
                throw e
            } catch (e: UnknownHostException) {
                throw UserFacingException("주소를 찾을 수 없어요. 서버 주소를 다시 확인해 주세요.")
            } catch (e: SocketTimeoutException) {
                dropConnection()
                throw UserFacingException(
                    "서버 응답이 없어요.\n외부 접속이라면 공유기 포트포워딩(${server.port}번)과 서버가 켜져 있는지 확인해 주세요."
                )
            } catch (e: ConnectException) {
                dropConnection()
                throw UserFacingException(
                    "서버에 연결할 수 없어요.\n주소·포트가 맞는지, 외부 접속이라면 포트포워딩이 돼 있는지 확인해 주세요."
                )
            } catch (e: NoRouteToHostException) {
                dropConnection()
                throw UserFacingException("서버까지 가는 경로가 없어요. 인터넷 연결과 주소를 확인해 주세요.")
            } catch (e: IOException) {
                dropConnection()
                throw UserFacingException("연결 중 문제가 생겼어요: ${e.message ?: e.javaClass.simpleName}")
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
            throw UserFacingException("서버가 접속을 거부했어요 (응답 $code).")
        }
        client.soTimeout = 20_000
        client.setDataTimeout(Duration.ofSeconds(20))

        val user = server.user.ifBlank { "anonymous" }
        val pass = if (server.user.isBlank()) "guest@" else server.password
        if (!client.login(user, pass)) {
            dropConnection()
            throw UserFacingException("로그인에 실패했어요. 아이디와 비밀번호를 확인해 주세요.")
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
}
