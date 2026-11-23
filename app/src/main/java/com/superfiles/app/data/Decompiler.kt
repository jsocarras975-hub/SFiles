package com.superfiles.app.data

import android.content.Context
import android.os.Environment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.zip.ZipFile
import org.jf.baksmali.Adaptors.ClassDefinition
import org.jf.baksmali.BaksmaliOptions
import org.jf.baksmali.formatter.BaksmaliWriter
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.jf.util.IndentingWriter

/**
 * Full decompile/recompile, apktool-style:
 *
 * - Decompile writes a project folder: AndroidManifest.xml and every resource
 *   XML decoded to readable text, smali/ with every class disassembled, res/ and
 *   assets/ extracted as they are.
 * - Rebuild reassembles the smali tree back into dex files and rebuilds the
 *   APK (aligned, re-signed). Edits to .smali files are picked up; edits to
 *   decoded .xml files are NOT recompiled yet — there is no binary XML
 *   encoder in this version, so the original binary resources are reused.
 */
object Decompiler {

    private const val API = 34

    data class Project(
        val dir: File,
        val dexDirs: List<String>,
        val smaliFiles: Int,
        val xmlFiles: Int,
    )

    fun projectDir(ctx: Context, apk: File): File {
        return File(
            File(ctx.cacheDir, "decompiled"),
            apk.nameWithoutExtension,
        )
    }

    /** Decodes [apk] into a project folder and returns its layout. */
    fun decompile(ctx: Context, apk: File): Project {
        val dir = projectDir(ctx, apk).apply { deleteRecursively(); mkdirs() }
        var smaliFiles = 0
        var xmlFiles = 0
        ZipFile(apk).use { z ->
            // Manifest + all binary XML resources, decoded to text.
            for (e in z.entries().asSequence()) {
                if (e.isDirectory) continue
                val bytes = z.getInputStream(e).readBytes()
                when {
                    e.name == "AndroidManifest.xml" -> {
                        File(dir, "AndroidManifest.xml").writeText(AxmlDecoder.decode(bytes))
                        xmlFiles++
                    }
                    e.name.startsWith("res/") && e.name.endsWith(".xml") -> {
                        val out = File(dir, e.name).apply { parentFile?.mkdirs() }
                        out.writeText(AxmlDecoder.decode(bytes))
                        xmlFiles++
                    }
                    e.name.startsWith("res/") || e.name.startsWith("assets/") -> {
                        val out = File(dir, e.name).apply { parentFile?.mkdirs() }
                        out.writeBytes(bytes)
                    }
                }
            }
            // Every dex file, disassembled class by class.
            val dexDirs = ArrayList<String>()
            for (e in z.entries().asSequence().filter { Regex("classes\\d*\\.dex").matches(it.name) }.sortedBy { it.name }) {
                val sub = if (e.name == "classes.dex") "smali" else "smali_${e.name.removePrefix("classes").removeSuffix(".dex")}"
                val dex = DexBackedDexFile(Opcodes.forApi(API), z.getInputStream(e).readBytes())
                for (def in dex.classes) {
                    val rel = def.type.removePrefix("L").removeSuffix(";") + ".smali"
                    val out = File(dir, "$sub/$rel").apply { parentFile?.mkdirs() }
                    val sw = java.io.StringWriter()
                    val w = IndentingWriter(sw)
                    ClassDefinition(BaksmaliOptions(), def).writeTo(BaksmaliWriter(w))
                    w.flush()
                    out.writeText(sw.toString())
                    smaliFiles++
                }
                dexDirs += sub
            }
            File(dir, "README.txt").writeText(
                "Decompiled ${apk.name}\n\n" +
                    "- Edit .smali files in smali/ and tap Rebuild APK to reassemble them.\n" +
                    "- AndroidManifest.xml and res/*.xml are decoded for reading. Edits to\n" +
                    "  those files are NOT recompiled yet — the original binary resources\n" +
                    "  are reused on rebuild.\n" +
                    "- Files under res/ and assets/ are the originals, byte for byte.\n",
            )
            return Project(dir, dexDirs, smaliFiles, xmlFiles)
        }
    }

    /** Reassembles every smali tree into its dex file and rebuilds the APK. */
    fun rebuild(ctx: Context, apk: File, project: Project): File {
        val work = File(ctx.cacheDir, "decompile-build").apply { deleteRecursively(); mkdirs() }
        val dexReplacements = HashMap<String, File>()
        for (sub in project.dexDirs) {
            val dexName = if (sub == "smali") "classes.dex" else "classes${sub.removePrefix("smali_")}.dex"
            val srcDir = File(project.dir, sub)
            val outDex = File(work, dexName)
            val options = SmaliOptions().apply {
                apiLevel = API
                outputDexFile = outDex.absolutePath
                jobs = 1
            }
            val captured = ByteArrayOutputStream()
            val oldErr = System.err
            System.setErr(PrintStream(captured, true))
            val ok = try {
                Smali.assemble(options, srcDir.absolutePath)
            } finally {
                System.setErr(oldErr)
            }
            if (!ok || !outDex.exists()) {
                error("Smali assembly failed for $dexName: ${captured.toString().trim().ifEmpty { "unknown error" }}")
            }
            dexReplacements[dexName] = outDex
        }
        val outDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SuperFiles",
        ).apply { mkdirs() }
        val out = File(outDir, apk.nameWithoutExtension + "-rebuilt.apk")
        val overview = ApkInspector.overview(ctx, apk)
        ApkEditor.build(
            ctx, apk, ManifestEdits(), overview.minSdk, out,
            dexReplacements = dexReplacements,
        )
        return out
    }
}
