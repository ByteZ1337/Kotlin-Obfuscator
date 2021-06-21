package xyz.xenondevs.vetric.transformer.obfuscation.renamer

import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import xyz.xenondevs.vetric.exclusion.ExclusionManager
import xyz.xenondevs.vetric.jvm.ClassWrapper
import xyz.xenondevs.vetric.jvm.JavaArchive
import xyz.xenondevs.vetric.supplier.StringSupplier
import xyz.xenondevs.vetric.util.asm.ASMUtils
import java.util.concurrent.atomic.AtomicInteger

class MappingsGenerator(private val jar: JavaArchive) {
    
    fun generateMappings(): MutableMap<String, String> {
        val mappings = HashMap<String, String>()
        jar.classes.filterNot(ExclusionManager::isExcluded).forEach { clazz ->
            if (Renamer.renameFields && !clazz.fields.isNullOrEmpty())
                generateFieldMappings(clazz, Renamer.fieldsSupplier, mappings)
            if (Renamer.renameMethods && !clazz.methods.isNullOrEmpty())
                generateMethodMappings(clazz, Renamer.methodsSupplier, mappings)
            if (Renamer.renameClasses)
                mappings[clazz.name] = Renamer.classesSupplier.randomStringUnique()
        }
        return mappings
    }
    
    private fun generateMethodMappings(clazz: ClassWrapper, supplier: StringSupplier, mappings: MutableMap<String, String>) {
        val occurrenceMap = getOccurrenceMap(clazz.methods, MethodNode::desc)
        val indexMap = occurrenceMap.mapValues { AtomicInteger(0) }
        val nameList = getNeededNames(supplier, occurrenceMap)
        
        clazz.methods.filter { ASMUtils.isRenameable(it, clazz) }.forEach { method ->
            // Get the current index of the descriptor, then increase the index.
            val index = indexMap[method.desc]!!.getAndIncrement()
            // Use the index to retrieve the current name.
            val newName = nameList[index]
            
            // Add the new name to the mappings.
            val methodPath = clazz.name + '.' + method.name + '.' + method.desc
            mappings[methodPath] = newName
            clazz.getFullSubClasses().forEach { Renamer.mappings["$it.${method.name}${method.desc}"] = newName }
        }
    }
    
    private fun generateFieldMappings(clazz: ClassWrapper, supplier: StringSupplier, mappings: MutableMap<String, String>) {
        val occurrenceMap = getOccurrenceMap(clazz.fields, FieldNode::desc)
        val indexMap = occurrenceMap.mapValues { AtomicInteger(0) }
        val nameList = getNeededNames(supplier, occurrenceMap)
        clazz.fields.filterNot { field -> ExclusionManager.isExcluded(clazz, field) }.forEach { field ->
            if (clazz.isEnum() && "\$VALUES" == field.name)
                return@forEach
            
            // Get the current index of the descriptor, then increase the index.
            val index = indexMap[field.desc]!!.getAndIncrement()
            // Use the index to retrieve the current name.
            val newName = nameList[index]
            
            // Add the new name to the mappings.
            val fieldPath = clazz.name + '.' + field.name + '.' + field.desc
            mappings[fieldPath] = newName
        }
    }
    
    private fun <T> getOccurrenceMap(list: List<T>, mapper: (T) -> String): Map<String, Int> =
        list.groupingBy(mapper).eachCount()
    
    private fun getNeededNames(supplier: StringSupplier, occurrenceMap: Map<String, Int>): List<String> {
        // Get the max occurrences of all descriptors
        val amount = occurrenceMap.values.maxOrNull() ?: return emptyList()
        
        // Generate needed amount of names.
        val names = HashSet<String>()
        repeat(amount) { names += supplier.randomStringUnique(names) }
        return names.toList()
    }
    
}