package com.superfiles.app.data

import org.jf.dexlib2.HiddenApiRestriction
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.Annotation
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.MethodParameter
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableField
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.value.ImmutableIntEncodedValue
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.jf.dexlib2.writer.pool.DexPool
import java.io.File
import java.util.zip.ZipFile

/**
 * Structured DEX editing: add or remove classes' methods and fields, or rename
 * a class, without touching smali text. Edits are pure functions over the dex
 * file — each returns a new ImmutableDexFile, so the UI can stack them and
 * write the result once at save time.
 *
 * Limits: renaming a class only changes that class's own definition — method
 * calls and field references to it from other classes are not rewritten.
 */
object DexEditor {

    data class ClassInfo(val type: String, val methods: Int, val fields: Int)
    data class MethodInfo(val name: String, val returnType: String, val params: List<String>)
    data class FieldInfo(val name: String, val type: String)

    private const val API = 34
    private val DEX_NAME = Regex("classes\\d*\\.dex")

    fun dexNames(apk: File): List<String> = ZipFile(apk).use { z ->
        z.entries().asSequence().map { it.name }.filter { DEX_NAME.matches(it) }.sorted().toList()
    }

    fun loadDex(apk: File, dexName: String): DexBackedDexFile {
        val bytes = ZipFile(apk).use { z ->
            val e = z.getEntry(dexName) ?: error("$dexName not found in the APK")
            z.getInputStream(e).readBytes()
        }
        return DexBackedDexFile(Opcodes.forApi(API), bytes)
    }

    fun classes(dex: DexFile): List<ClassInfo> =
        dex.classes.map { ClassInfo(it.type, it.methods.count(), it.fields.count()) }
            .sortedBy { it.type }

    fun methods(dex: DexFile, type: String): List<MethodInfo> {
        val def = dex.classes.firstOrNull { it.type == type } ?: return emptyList()
        return def.methods.map { MethodInfo(it.name, it.returnType, it.parameters.map { p -> p.type }, ) }
    }

    fun fields(dex: DexFile, type: String): List<FieldInfo> {
        val def = dex.classes.firstOrNull { it.type == type } ?: return emptyList()
        return def.fields.map { FieldInfo(it.name, it.type) }
    }

    fun removeMethod(dex: DexFile, type: String, name: String, params: List<String>): ImmutableDexFile {
        return rebuild(dex) { def ->
            if (def.type != type) return@rebuild def
            require(def.methods.any { it.name == name && it.parameters.map { p -> p.type } == params }) {
                "Method not found"
            }
            ImmutableClassDef(
                def.type, def.accessFlags, def.superclass, def.interfaces, def.sourceFile,
                def.annotations, def.fields,
                def.methods.filterNot { it.name == name && it.parameters.map { p -> p.type } == params },
            )
        }
    }

    fun removeField(dex: DexFile, type: String, name: String): ImmutableDexFile {
        return rebuild(dex) { def ->
            if (def.type != type) return@rebuild def
            require(def.fields.any { it.name == name }) { "Field not found" }
            ImmutableClassDef(
                def.type, def.accessFlags, def.superclass, def.interfaces, def.sourceFile,
                def.annotations, def.fields.filterNot { it.name == name }, def.methods,
            )
        }
    }

    /** Adds an empty `public void name()` method. */
    fun addMethod(dex: DexFile, type: String, name: String): ImmutableDexFile {
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Not a valid method name" }
        return rebuild(dex) { def ->
            if (def.type != type) return@rebuild def
            require(def.methods.none { it.name == name && it.parameters.count() == 0 }) {
                "A no-arg method named $name already exists"
            }
            val impl = ImmutableMethodImplementation(
                0, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), emptyList(), emptyList(),
            )
            val method = ImmutableMethod(
                type, name, emptyList<MethodParameter>(), "V", 0x1,
                emptyList<Annotation>(), emptySet<HiddenApiRestriction>(), impl,
            )
            ImmutableClassDef(
                def.type, def.accessFlags, def.superclass, def.interfaces, def.sourceFile,
                def.annotations, def.fields, def.methods + method,
            )
        }
    }

    /** Adds a `public <typeDesc> name` field with a zero/empty default value. */
    fun addField(dex: DexFile, type: String, name: String, typeDesc: String): ImmutableDexFile {
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Not a valid field name" }
        return rebuild(dex) { def ->
            if (def.type != type) return@rebuild def
            require(def.fields.none { it.name == name }) { "A field named $name already exists" }
            val initial = when (typeDesc) {
                "I", "Z", "B", "C", "S", "J" -> ImmutableIntEncodedValue(0)
                "Ljava/lang/String;" -> ImmutableStringEncodedValue("")
                else -> null
            }
            val field = ImmutableField(
                type, name, typeDesc, 0x1, initial,
                emptyList<Annotation>(), emptySet<HiddenApiRestriction>(),
            )
            ImmutableClassDef(
                def.type, def.accessFlags, def.superclass, def.interfaces, def.sourceFile,
                def.annotations, def.fields + field, def.methods,
            )
        }
    }

    /** Renames a class's own definition (methods/fields move with it). Callers are not updated. */
    fun renameClass(dex: DexFile, oldType: String, newType: String): ImmutableDexFile {
        require(newType.matches(Regex("L[A-Za-z0-9_/\\$]+;"))) {
            "Class name must look like Lcom/example/Foo;"
        }
        require(dex.classes.none { it.type == newType }) { "A class named $newType already exists" }
        return rebuild(dex) { def ->
            if (def.type != oldType) return@rebuild def
            val methods = def.methods.map { m ->
                ImmutableMethod(
                    newType, m.name, m.parameters, m.returnType, m.accessFlags,
                    m.annotations, emptySet<HiddenApiRestriction>(), m.implementation,
                )
            }
            val fields = def.fields.map { f ->
                ImmutableField(
                    newType, f.name, f.type, f.accessFlags, f.initialValue,
                    f.annotations, emptySet<HiddenApiRestriction>(),
                )
            }
            ImmutableClassDef(
                newType, def.accessFlags, def.superclass, def.interfaces, def.sourceFile,
                def.annotations, fields, methods,
            )
        }
    }

    fun writeDex(dex: ImmutableDexFile, dst: File) {
        DexPool.writeTo(dst.absolutePath, dex)
    }

    private fun rebuild(dex: DexFile, transform: (ClassDef) -> ClassDef?): ImmutableDexFile {
        val classes = dex.classes.mapNotNull { transform(it) }
        return ImmutableDexFile(Opcodes.forApi(API), classes)
    }
}
