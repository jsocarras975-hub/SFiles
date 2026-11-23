package com.superfiles.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

data class CertInfo(val subject: String, val sha256: String, val validFrom: String, val validTo: String)

data class ZipRow(val name: String, val size: Long, val compressed: Long)

/** Which APK signature schemes are present (v1 = jar signing in META-INF, v2/v3/v3.1 = signing block). */
data class SignatureStatus(val v1: Boolean, val v2: Boolean, val v3: Boolean, val v31: Boolean)

data class ApkOverview(
    val pkg: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val permissions: List<String>,
    val activities: List<String>,
    val services: List<String>,
    val receivers: List<String>,
    val providers: List<String>,
    val certs: List<CertInfo>,
    val sig: SignatureStatus,
)

object ApkInspector {

    fun overview(ctx: Context, apk: File): ApkOverview {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.GET_SIGNING_CERTIFICATES
        val info = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: error("Not a valid APK")
        val app = info.applicationInfo
        app?.sourceDir = apk.absolutePath
        app?.publicSourceDir = apk.absolutePath
        val signers = info.signingInfo?.apkContentsSigners ?: emptyArray()
        return ApkOverview(
            pkg = info.packageName,
            label = app?.loadLabel(pm)?.toString().orEmpty(),
            versionName = info.versionName.orEmpty(),
            versionCode = info.longVersionCode,
            minSdk = app?.minSdkVersion ?: 0,
            targetSdk = app?.targetSdkVersion ?: 0,
            permissions = info.requestedPermissions?.toList().orEmpty(),
            activities = info.activities?.map { it.name }.orEmpty(),
            services = info.services?.map { it.name }.orEmpty(),
            receivers = info.receivers?.map { it.name }.orEmpty(),
            providers = info.providers?.map { it.name }.orEmpty(),
            certs = signers.map { cert(it) },
            sig = signatureStatus(apk),
        )
    }

    /** Raw bytes of one zip entry. */
    fun readEntry(apk: File, name: String): ByteArray = ZipFile(apk).use { z ->
        val e = z.getEntry(name) ?: error("Entry not found: $name")
        z.getInputStream(e).readBytes()
    }

    /** Copies one entry out of the APK into [destDir], keeping its path inside it. */
    fun extractEntry(apk: File, entryName: String, destDir: File): File {
        ZipFile(apk).use { z ->
            val e = z.getEntry(entryName) ?: error("Entry not found")
            val dest = File(destDir, entryName).apply { parentFile?.mkdirs() }
            z.getInputStream(e).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return dest
        }
    }

    /** Reads the APK Signing Block footer to see which schemes signed the APK. */
    fun signatureStatus(apk: File): SignatureStatus {
        var v1 = false
        var v2 = false
        var v3 = false
        var v31 = false
        ZipFile(apk).use { z ->
            v1 = z.entries().asSequence().any { e ->
                e.name.startsWith("META-INF/") && (e.name.endsWith(".SF") || e.name.endsWith(".RSA") ||
                    e.name.endsWith(".DSA") || e.name.endsWith(".EC"))
            }
        }
        runCatching {
            RandomAccessFile(apk, "r").use { raf ->
                val len = raf.length()
                if (len < 22) return@runCatching
                // Find the End of Central Directory record in the last 64 KiB + 22 bytes.
                val tailSize = minOf(len, 65557L + 22).toInt()
                val tail = ByteArray(tailSize)
                raf.seek(len - tailSize)
                raf.readFully(tail)
                var eocd = -1
                for (i in tailSize - 22 downTo 0) {
                    if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4B.toByte() &&
                        tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
                    ) { eocd = i; break }
                }
                if (eocd < 0) return@runCatching
                val bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
                val cdOffset = bb.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
                // Signing block footer: 8-byte size + 16-byte magic, right before the central directory.
                val footerPos = cdOffset - 24
                if (footerPos < 0) return@runCatching
                raf.seek(footerPos)
                val footer = ByteArray(24)
                raf.readFully(footer)
                val magic = "APK Sig Block 42".toByteArray(Charsets.UTF_8)
                if (!footer.copyOfRange(8, 24).contentEquals(magic)) return@runCatching
                val blockSize = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
                val pairsStart = cdOffset - blockSize + 8
                val pairsEnd = cdOffset - 24
                if (blockSize < 32 || pairsStart < 0 || pairsEnd - pairsStart > 16 * 1024 * 1024) return@runCatching
                raf.seek(pairsStart)
                val block = ByteArray((pairsEnd - pairsStart).toInt())
                raf.readFully(block)
                val pb = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
                while (pb.remaining() >= 12) {
                    val entryLen = pb.getLong()
                    if (entryLen < 4 || entryLen > pb.remaining()) break
                    val id = pb.getInt()
                    when (id) {
                        0x7109871a -> v2 = true
                        0xf05368c0 -> v3 = true
                        0x1b93ad61 -> v31 = true
                    }
                    pb.position(pb.position() + (entryLen - 4).toInt())
                }
            }
        }
        return SignatureStatus(v1, v2, v3, v31)
    }

    fun manifest(apk: File): String = ZipFile(apk).use { z ->
        val entry = z.getEntry("AndroidManifest.xml") ?: error("No AndroidManifest.xml in this archive")
        AxmlDecoder.decode(z.getInputStream(entry).readBytes())
    }

    fun entries(apk: File): List<ZipRow> = ZipFile(apk).use { z ->
        z.entries().asSequence()
            .filter { !it.isDirectory }
            .map { ZipRow(it.name, it.size, it.compressedSize) }
            .sortedBy { it.name }
            .toList()
    }

    fun classes(apk: File): List<String> = ZipFile(apk).use { z ->
        val dexRegex = Regex("classes\\d*\\.dex")
        z.entries().asSequence()
            .filter { dexRegex.matches(it.name) }
            .flatMap { DexReader.classNames(z.getInputStream(it).readBytes()) }
            .sorted()
            .toList()
    }

    private fun cert(sig: Signature): CertInfo {
        val der = sig.toByteArray()
        val x = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        val sha = MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }
        return CertInfo(x.subjectX500Principal.name, sha, x.notBefore.toString(), x.notAfter.toString())
    }
}
