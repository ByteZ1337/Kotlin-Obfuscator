package xyz.xenondevs.vetric.config

import com.google.gson.JsonObject
import xyz.xenondevs.vetric.jvm.Library
import xyz.xenondevs.vetric.transformers.TransformerRegistry
import xyz.xenondevs.vetric.utils.json.JsonConfig
import java.io.File

object ObfuscatorConfig {
    
    lateinit var input: File
    lateinit var output: File
    lateinit var libraries: List<Library>
    
    fun load(config: JsonConfig) {
        input = config.getOrThrow(ConfigSetting.INPUT) { "Missing input file in config." }
        output = config.getOrThrow(ConfigSetting.OUTPUT) { "Missing output file in config." }
        libraries = config.getOrDefault(ConfigSetting.LIBRARIES, emptyList())
        
        if (config.contains<JsonObject>("transformers")) {
            TransformerRegistry.transformers.forEach {
                val key = "transformers.${it.name.toLowerCase()}"
                if (key in config && it.config.isValid(key, config, true))
                    it.config.parse(key, config)
            }
        }
    }
}