package xyz.xenondevs.vetric.exclusion

import com.google.gson.JsonObject
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import xyz.xenondevs.vetric.jvm.ClassWrapper
import xyz.xenondevs.vetric.util.json.JsonConfig
import xyz.xenondevs.vetric.util.json.getString
import xyz.xenondevs.vetric.util.json.hasArray
import xyz.xenondevs.vetric.util.json.hasString

object ExclusionManager {
    
    private var exclusions = emptyList<String>()
    private lateinit var script: String
    
    fun isExcluded(clazz: ClassWrapper): Boolean {
        return clazz.name.startsWith("kotlin/") || clazz.name in exclusions
    }
    
    fun isExcluded(owner: ClassWrapper, field: FieldNode): Boolean {
        return "${owner.name}.${field.name}" in exclusions
            || "${owner.name}.${field.name}.${field.desc}" in exclusions
    }
    
    fun isExcluded(owner: ClassWrapper, method: MethodNode): Boolean {
        return "${owner.name}.${method.name}" in exclusions
            || "${owner.name}.${method.name}.${method.desc}" in exclusions
    }
    
    fun parseConfig(config: JsonConfig) {
        if (!config.contains<JsonObject>("exclusion"))
            return
        val exclConfig = config.getObject("exclusion")!!
        
        if (exclConfig.hasArray("exclusions"))
            exclusions = ExclusionListType.parseElement(exclConfig.getAsJsonArray("exclusions"))
        if (exclConfig.hasString("script"))
            script = exclConfig.getString("script")!!
    }
    
}