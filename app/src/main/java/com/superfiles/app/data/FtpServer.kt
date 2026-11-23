package com.superfiles.app.data

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A small FTP server for wireless file transfer on the local network. It only
 * runs while the user starts it, only listens on the Wi-Fi LAN, and only
 * serves files under [root]. Any username/password is accepted.
 */
object FtpServer {

    const val PORT = 2121

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val stopFlag = AtomicBoolean(false)
    private lateinit var root: File

    fun localIp(ctx: Context): String? {
        @Suppress("DEPRECATION")
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val ip = wifi.connectionInfo.ipAddress
        if (ip == 0) return null
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    fun start(rootDir: File): Boolean {
        if (_running.value) return true
        root = rootDir
        stopFlag.set(false)
        return try {
            server = ServerSocket(PORT)
            _running.value = true
            log("FTP server started on port $PORT")
            acceptThread = thread(name = "ftp-accept", isDaemon = true) {
                while (!stopFlag.get()) {
                    try {
                        val socket = server?.accept() ?: break
                        thread(name = "ftp-client", isDaemon = true) { handle(socket) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            true
        } catch (e: Exception) {
            log("Couldn't start: ${e.message}")
            false
        }
    }

    fun stop() {
        stopFlag.set(true)
        runCatching { server?.close() }
        server = null
        _running.value = false
        log("FTP server stopped")
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _log.value = (_log.value + "[$time] $msg").takeLast(100)
    }

    /** Resolves a client path under the root, rejecting escapes like "..". */
    private fun resolve(cwd: String, arg: String): File? {
        val base = if (arg.startsWith("/")) arg.removePrefix("/") else "$cwd/$arg".trimStart('/')
        val f = File(root, base).canonicalFile
        return if (f == root || f.path.startsWith(root.canonicalPath + File.separator)) f else null
    }

    private fun listLine(f: File): String {
        val date = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(f.lastModified()))
        val perms = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
        return "$perms 1 owner group ${f.length()} $date ${f.name}"
    }

    private fun handle(socket: Socket) {
        log("Client connected: ${socket.inetAddress.hostAddress}")
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val out = PrintWriter(s.getOutputStream(), true)
            var cwd = ""
            var dataServer: ServerSocket? = null
            var renameFrom: File? = null

            fun reply(code: Int, msg: String) = out.println("$code $msg")

            fun openData(): Socket? {
                val ds = dataServer ?: run { reply(425, "Use PASV first"); return null }
                dataServer = null
                return try {
                    ds.accept().also { ds.close() }
                } catch (e: Exception) {
                    reply(425, "Data connection failed: ${e.message}")
                    null
                }
            }

            reply(220, "SuperFiles FTP ready")
            while (true) {
                val line = reader.readLine() ?: break
                val sp = line.indexOf(' ')
                val cmd = (if (sp >= 0) line.substring(0, sp) else line).uppercase()
                val arg = if (sp >= 0) line.substring(sp + 1).trim() else ""
                when (cmd) {
                    "USER" -> reply(331, "Password required")
                    "PASS" -> reply(230, "Logged in")
                    "SYST" -> reply(215, "UNIX Type: L8")
                    "FEAT" -> {
                        out.println("211-Features:")
                        out.println(" PASV")
                        out.println(" SIZE")
                        reply(211, "End")
                    }
                    "PWD" -> reply(257, "\"/$cwd\" is the current directory")
                    "CWD" -> {
                        val target = resolve(cwd, arg)
                        if (target == null || !target.isDirectory) reply(550, "No such directory")
                        else {
                            cwd = target.relativeTo(root).path.let { if (it == ".") "" else it }
                            reply(250, "Directory changed")
                        }
                    }
                    "CDUP" -> {
                        val parent = File(root, cwd).parentFile
                        cwd = if (parent == null || parent == root) "" else parent.relativeTo(root).path
                        reply(250, "Directory changed")
                    }
                    "TYPE" -> reply(200, "Type set to ${arg.ifEmpty { "I" }}")
                    "PASV" -> {
                        runCatching { dataServer?.close() }
                        val ds = ServerSocket(0)
                        dataServer = ds
                        val ip = socket.localAddress.hostAddress?.replace('.', ',') ?: "127,0,0,1"
                        val p = ds.localPort
                        reply(227, "Entering Passive Mode ($ip,${p / 256},${p % 256})")
                    }
                    "LIST", "NLST" -> {
                        val dir = resolve(cwd, arg.ifEmpty { "." }) ?: run { reply(550, "Not found"); null }
                        if (dir == null) continue
                        val data = openData() ?: continue
                        reply(150, "Opening data connection")
                        data.use { d ->
                            val sink: OutputStream = d.getOutputStream()
                            val children = dir.listFiles()?.sortedBy { it.name }.orEmpty()
                            for (f in children) {
                                val text = if (cmd == "NLST") f.name else listLine(f)
                                sink.write((text + "\r\n").toByteArray())
                            }
                            sink.flush()
                        }
                        reply(226, "Transfer complete")
                    }
                    "RETR" -> {
                        val f = resolve(cwd, arg)
                        if (f == null || !f.isFile) { reply(550, "Not found"); continue }
                        val data = openData() ?: continue
                        reply(150, "Opening data connection")
                        data.use { d ->
                            f.inputStream().use { it.copyTo(d.getOutputStream()) }
                        }
                        log("Sent ${f.name}")
                        reply(226, "Transfer complete")
                    }
                    "STOR" -> {
                        val f = resolve(cwd, arg)
                        if (f == null || f.isDirectory) { reply(550, "Bad path"); continue }
                        val data = openData() ?: continue
                        reply(150, "Opening data connection")
                        f.parentFile?.mkdirs()
                        data.use { d ->
                            f.outputStream().use { d.getInputStream().copyTo(it) }
                        }
                        log("Received ${f.name}")
                        reply(226, "Transfer complete")
                    }
                    "SIZE" -> {
                        val f = resolve(cwd, arg)
                        if (f == null || !f.isFile) reply(550, "Not found")
                        else reply(213, "${f.length()}")
                    }
                    "DELE" -> {
                        val f = resolve(cwd, arg)
                        if (f == null || !f.isFile || !f.delete()) reply(550, "Couldn't delete")
                        else reply(250, "Deleted")
                    }
                    "MKD" -> {
                        val f = resolve(cwd, arg)
                        if (f == null || !f.mkdirs()) reply(550, "Couldn't create directory")
                        else reply(257, "\"$arg\" created")
                    }
                    "RMD" -> {
                        val f = resolve(cwd, arg)
                        if (f == null || !f.isDirectory || !f.delete()) reply(550, "Couldn't remove directory")
                        else reply(250, "Removed")
                    }
                    "RNFR" -> {
                        val f = resolve(cwd, arg)
                        if (f == null || !f.exists()) reply(550, "Not found")
                        else { renameFrom = f; reply(350, "Ready for RNTO") }
                    }
                    "RNTO" -> {
                        val from = renameFrom
                        val to = resolve(cwd, arg)
                        if (from == null || to == null || !from.renameTo(to)) reply(550, "Rename failed")
                        else reply(250, "Renamed")
                        renameFrom = null
                    }
                    "NOOP" -> reply(200, "OK")
                    "QUIT" -> { reply(221, "Goodbye"); break }
                    else -> reply(502, "Command not implemented")
                }
            }
        }
        log("Client disconnected")
    }
}
