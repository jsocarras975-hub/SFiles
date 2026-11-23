package com.superfiles.app.data

import android.content.Context
import org.jf.baksmali.Adaptors.ClassDefinition
import org.jf.baksmali.BaksmaliOptions
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.writer.pool.DexPool
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.jf.util.IndentingWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.StringWriter
import java.util.zip.ZipFile

class SmaliEdit(val dexEntry: String, val classType: String, val text: String)

/** Smali edits waiting for the next "Build and sign", kept in memory per APK. */
object SmaliStaging {
    private val byApk = HashMap<String, LinkedHashMap<String, SmaliEdit>>()

    fun put(apk: File, e: SmaliEdit) {
        byApk.getOrPut(apk.absolutePath) { LinkedHashMap() }[e.classType] = e
    }
    fun get(apk: File, type: String): SmaliEdit? = byApk[apk.absolutePath]?.get(type)
    fun has(apk: File, type: String) = get(apk, type) != null
    fun list(apk: File): List<SmaliEdit> = byApk[apk.absolutePath]?.values?.toList().orEmpty()
    fun count(apk: File) = byApk[apk.absolutePath]?.size ?: 0
    fun clear(apk: File) { byApk.remove(apk.absolutePath) }
}

object SmaliTools {
    // Newest API level dexlib2 2.5.2 understands, so APKs targeting current Android
    // disassemble instead of failing on newer opcodes.
    private const val API = 34

    data class ClassRef(val dex: String, val type: String) {
        val display: String get() = type.removePrefix("L").removeSuffix(";").replace('/', '.')
    }

    fun classRefs(apk: File): List<ClassRef> = ZipFile(apk).use { z ->
        val dexName = Regex("classes\\d*\\.dex")
        z.entries().asSequence()
            .filter { dexName.matches(it.name) }
            .sortedBy { it.name }
            .flatMap { e ->
                DexReader.classNames(z.getInputStream(e).readBytes())
                    .map { n -> ClassRef(e.name, "L" + n.replace('.', '/') + ";") }
            }
            .sortedBy { it.type }
            .toList()
    }

    fun disassemble(apk: File, ref: ClassRef): String {
        val dex = DexBackedDexFile(Opcodes.forApi(API), readEntry(apk, ref.dex))
        val def = dex.classes.firstOrNull { it.type == ref.type } ?: error("Class not found in ${ref.dex}")
        val sw = StringWriter()
        val w = IndentingWriter(sw)
        ClassDefinition(BaksmaliOptions(), def).writeTo(w)
        w.flush()
        return sw.toString()
    }

    /** Assembles [text] on its own and checks it still defines [expectedType]. Throws with the error text. */
    fun check(ctx: Context, text: String, expectedType: String) {
        // A unique work dir per call, so two saves racing each other can't clobber the same files.
        val cd = assemble(text, File(ctx.cacheDir, "smali-check-${System.nanoTime()}"))
        require(cd.type == expectedType) { "The class name must stay $expectedType (found ${cd.type})" }
    }

    /** Rebuilds every dex that has staged edits, swapping in the assembled classes. Returns entry name to file. */
    fun buildDexes(ctx: Context, apk: File, edits: List<SmaliEdit>): Map<String, File> {
        val work = File(ctx.cacheDir, "smali-build").apply { deleteRecursively(); mkdirs() }
        val result = HashMap<String, File>()
        for ((dexName, group) in edits.groupBy { it.dexEntry }) {
            val opcodes = Opcodes.forApi(API)
            val orig = DexBackedDexFile(opcodes, readEntry(apk, dexName))
            val replaced = HashMap<String, ClassDef>()
            for (e in group) {
                val cd = assemble(e.text, File(work, "asm-${System.nanoTime()}"))
                require(cd.type == e.classType) { "The class name must stay ${e.classType} (found ${cd.type})" }
                replaced[e.classType] = cd
            }
            val classes: List<ClassDef> = orig.classes.map { replaced[it.type] ?: it }
            val dst = File(work, dexName)
            DexPool.writeTo(dst.absolutePath, ImmutableDexFile(opcodes, classes))
            result[dexName] = dst
        }
        return result
    }

    private fun readEntry(apk: File, name: String): ByteArray = ZipFile(apk).use { z ->
        val e = z.getEntry(name) ?: error("$name not found in the APK")
        z.getInputStream(e).readBytes()
    }

    private fun assemble(text: String, dir: File): ClassDef {
        dir.deleteRecursively()
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "Edit.smali").writeText(text)
        val outDex = File(dir, "single.dex")

        val options = SmaliOptions().apply {
            apiLevel = API
            outputDexFile = outDex.absolutePath
            jobs = 1
        }
        // smali reports syntax errors on System.err, so capture them to show the user.
        val captured = ByteArrayOutputStream()
        val oldErr = System.err
        System.setErr(PrintStream(captured, true))
        val ok = try {
            Smali.assemble(options, src.absolutePath)
        } finally {
            System.setErr(oldErr)
        }
        if (!ok || !outDex.exists()) {
            error(captured.toString().trim().ifEmpty { "Smali assembly failed" })
        }
        return DexBackedDexFile(Opcodes.forApi(API), outDex.readBytes()).classes.first()
    }
}
