package xyz.xenondevs.vetric.transformers.renamer

import com.google.gson.JsonObject
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import xyz.xenondevs.vetric.config.type.TransformerConfig
import xyz.xenondevs.vetric.jvm.ClassWrapper
import xyz.xenondevs.vetric.jvm.JavaArchive
import xyz.xenondevs.vetric.transformers.Transformer
import xyz.xenondevs.vetric.transformers.TransformerPriority.HIGHEST
import xyz.xenondevs.vetric.utils.*
import xyz.xenondevs.vetric.utils.asm.ASMUtils
import xyz.xenondevs.vetric.utils.json.getBoolean

// TODO When exclusion is added add, check to isMoveable
object Shuffler : Transformer("Shuffler", ShufflerConfig, HIGHEST) {
    
    var shuffleFields = true
    var shuffleMethods = true
    var crossClassMethods = false
    var crossClassFields = false
    
    val mappings = HashMap<String, String>()
    val processedMethods = ArrayList<String>()
    val processedFields = ArrayList<String>()
    
    override fun transformJar(jar: JavaArchive) {
        mappings.clear()
        if (crossClassFields) shuffleFieldsCC(jar)
        if (crossClassMethods) shuffleMethodsCC(jar)
        
        jar.classes.forEach {
            if (shuffleFields && !it.fields.isNullOrEmpty())
                it.fields.shuffle()
            if (shuffleMethods && !it.methods.isNullOrEmpty())
                it.methods.shuffle()
        }
        
        processedMethods.clear()
        processedFields.clear()
    }
    
    private fun shuffleFieldsCC(jar: JavaArchive) {
        val available = jar.classes.filter { it.accessWrapper.isPublicClass() }
        if (available.isEmpty())
            return
        
        available.forEach { clazz ->
            if (clazz.fields.isNullOrEmpty())
                return@forEach
            clazz.fields.filter { prepareField(clazz, it) }.forEach field@{ field ->
                val newClass = available.filter { field !in it }.randomOrNull() ?: return@field
                if (newClass.fields == null)
                    newClass.fields = ArrayList()
                newClass.fields.add(field)
                clazz.fields.remove(field)
                processedFields.add("${newClass.name}.${field.name}.${field.desc}")
                mappings["${clazz.name}.${field.name}.${field.desc}"] = newClass.name
            }
        }
        jar.forEachInstruction<FieldInsnNode>({ mappings.containsKey("${it.owner}.${it.name}.${it.desc}") }) {
            it.owner = mappings["${it.owner}.${it.name}.${it.desc}"]
        }
    }
    
    private fun prepareField(clazz: ClassWrapper, field: FieldNode): Boolean {
        if (!field.accessWrapper.hasFlags(ACC_STATIC, ACC_FINAL) || "${clazz.name}.${field.name}.${field.desc}" in processedFields)
            return false
        if (field.value != null)
            return true
        
        val classInit = clazz.getMethod("<clinit>", "()V") ?: return false
        val initializer = classInit.instructions.filterTypeAnd<FieldInsnNode> {
            it.opcode == PUTSTATIC && clazz.name == it.owner && field.name == it.name && field.desc == it.desc
        }.firstOrNull() ?: return false
        
        val prev = initializer.previous
        if (prev !is LdcInsnNode)
            return false
        
        field.value = prev.cst
        field.access = ACC_PUBLIC or ACC_STATIC or ACC_FINAL
        classInit.instructions.remove(prev, initializer)
        return true
    }
    
    private fun shuffleMethodsCC(jar: JavaArchive) {
        val available = jar.classes.filter { it.accessWrapper.isPublicClass() }
        if (available.isEmpty())
            return
        
        available.forEach { clazz ->
            if (clazz.methods.isNullOrEmpty())
                return@forEach
            clazz.methods.filter { this.isMoveable(it, clazz) }.forEach method@{ method ->
                val newClass = available.filter { method !in it }.randomOrNull() ?: return@method
                method.access = ACC_PUBLIC or ACC_STATIC
                newClass.methods.add(method)
                clazz.methods.remove(method)
                processedMethods.add("${newClass.name}.${method.name}${method.desc}")
                mappings["${clazz.name}.${method.name}${method.desc}"] = newClass.name
            }
        }
        replaceMethodReferences(jar)
    }
    
    private fun replaceMethodReferences(jar: JavaArchive) {
        jar.forEachInstruction { insn ->
            if (insn is MethodInsnNode && "${insn.owner}.${insn.name}${insn.desc}" in mappings)
                insn.owner = mappings["${insn.owner}.${insn.name}${insn.desc}"]
            else if (insn is InvokeDynamicInsnNode) {
                insn.bsmArgs?.forEachIndexed { index, obj ->
                    if (obj is Handle && "${obj.owner}.${obj.name}${obj.desc}" in mappings)
                        insn.bsmArgs[index] = Handle(obj.tag, mappings["${obj.owner}.${obj.name}${obj.desc}"], obj.name, obj.desc, obj.isInterface)
                }
            }
        }
    }
    
    // TODO Move
    private fun isMoveable(method: MethodNode, clazz: ClassWrapper): Boolean {
        if (!method.accessWrapper.isStatic()
            || "${clazz.name}.${method.name}${method.desc}" in processedMethods
            || !Renamer.isRenameable(method, clazz)
            || !Type.getArgumentTypes(method.desc).map(Type::clazz).all { it.accessWrapper.isPublic() }
            || !Type.getReturnType(method.desc).clazz.accessWrapper.isPublic())
            return false
        return method.instructions.all { insn ->
            when (insn) {
                is InvokeDynamicInsnNode -> return false
                is FieldInsnNode -> return@all insn.access.isPublic()
                    && insn.ownerWrapper.accessWrapper.isPublic()
                    && Type.getType(insn.desc).clazz.accessWrapper.isPublic()
                is MethodInsnNode -> return@all insn.access.isPublic()
                    && insn.ownerWrapper.accessWrapper.isPublic()
                    && Type.getArgumentTypes(insn.desc).all { it.clazz.accessWrapper.isPublic() }
                    && Type.getReturnType(insn.desc).clazz.accessWrapper.isPublic()
                is TypeInsnNode -> return@all ASMUtils.getType(insn.desc).clazz.accessWrapper.isPublic()
                else -> return@all true
            }
        }
    }
    
    object ShufflerConfig : TransformerConfig(Shuffler::class) {
        override fun parse(obj: JsonObject) {
            super.parse(obj)
            shuffleFields = obj.getBoolean("fields", true)
            shuffleMethods = obj.getBoolean("methods", true)
            crossClassMethods = obj.getBoolean("crossclassmethods", false)
            crossClassFields = obj.getBoolean("crossclassfields", false)
        }
    }
    
}