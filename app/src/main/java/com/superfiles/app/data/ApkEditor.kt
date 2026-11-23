package com.superfiles.app.data

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkEditor {

    /** Applies [edits], rewrites the archive with proper alignment, then signs it (v1+v2+v3). */
    fun build(
        ctx: Context, src: File, edits: ManifestEdits, minSdk: Int, dst: File,
        dexReplacements: Map<String, File> = emptyMap(),
        arscReplacement: ByteArray? = null,
        xmlReplacements: Map<String, ByteArray> = emptyMap(),
    ) {
        val tmp = File(ctx.cacheDir, "unsigned-aligned.apk")
        try {
            rewrite(src, tmp, edits, dexReplacements, arscReplacement, xmlReplacements)
            val key = SigningKeys.get(ctx)
            val signer = ApkSigner.SignerConfig.Builder("superfiles", key.key, listOf(key.cert)).build()
            dst.delete()
            ApkSigner.Builder(listOf(signer))
                .setInputApk(tmp)
                .setOutputApk(dst)
                .setMinSdkVersion(minSdk.coerceAtLeast(1))
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign()
        } finally {
            tmp.delete()
        }
    }

    private class CountingStream(private val sink: OutputStream) : OutputStream() {
        var count = 0L
            private set
        override fun write(b: Int) { sink.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { sink.write(b, off, len); count += len }
        override fun flush() = sink.flush()
        override fun close() = sink.close()
    }

    /**
     * A well-formed extra field (id 0x0000, zero data) of [extraLen] bytes that keeps the
     * entry data aligned. [extraLen] is congruent to [pad] mod [align] and at least 4 bytes
     * when non-zero, so it always parses as exactly one extra field instead of raw padding
     * bytes that strict ZIP readers may reject.
     */
    private fun alignmentExtra(pad: Int, align: Int): ByteArray {
        var extraLen = pad
        if (extraLen in 1..3) extraLen += align
        val extra = ByteArray(extraLen)
        val size = extraLen - 4
        extra[2] = (size and 0xFF).toByte()
        extra[3] = ((size shr 8) and 0xFF).toByte()
        return extra
    }

    private fun isOldSignature(name: String): Boolean {
        val n = name.uppercase()
        return n.startsWith("META-INF/") && (n == "META-INF/MANIFEST.MF" || n.endsWith(".SF") ||
            n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))
    }

    private fun rewrite(
        src: File, dst: File, edits: ManifestEdits, dexReplacements: Map<String, File>,
        arscReplacement: ByteArray?, xmlReplacements: Map<String, ByteArray>,
    ) {
        ZipFile(src).use { zin ->
            val counter = CountingStream(BufferedOutputStream(FileOutputStream(dst)))
            ZipOutputStream(counter).use { zout ->
                for (e in zin.entries().asSequence()) {
                    if (e.isDirectory || isOldSignature(e.name)) continue
                    val edited = when {
                        e.name == "AndroidManifest.xml" -> AxmlEditor.edit(zin.getInputStream(e).readBytes(), edits)
                        e.name == "resources.arsc" && arscReplacement != null -> arscReplacement
                        e.name in dexReplacements -> dexReplacements.getValue(e.name).readBytes()
                        e.name in xmlReplacements -> xmlReplacements.getValue(e.name)
                        else -> null
                    }

                    val out = ZipEntry(e.name)
                    if (e.method == ZipEntry.STORED) {
                        val size = edited?.size?.toLong() ?: e.size
                        out.method = ZipEntry.STORED
                        out.size = size
                        out.compressedSize = size
                        out.crc = edited?.let { CRC32().apply { update(it) }.value } ?: e.crc
                        // Stored entries must start on a 4-byte boundary (16 KB for native libs).
                        val align = if (e.name.endsWith(".so")) 16384 else 4
                        val dataStart = counter.count + 30 + e.name.toByteArray(Charsets.UTF_8).size
                        val pad = ((align - dataStart % align) % align).toInt()
                        if (pad > 0) out.extra = alignmentExtra(pad, align)
                    } else {
                        out.method = ZipEntry.DEFLATED
                    }
                    zout.putNextEntry(out)
                    if (edited != null) zout.write(edited) else zin.getInputStream(e).use { it.copyTo(zout) }
                    zout.closeEntry()
                }
            }
        }
    }
}
