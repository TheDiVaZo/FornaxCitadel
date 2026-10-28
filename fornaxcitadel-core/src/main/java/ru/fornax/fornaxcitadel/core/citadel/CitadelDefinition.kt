package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.util.Key
import java.util.Collections

/** Registered once at startup; initializer instances may be shared by all citadels of this kind. */
class CitadelDefinition(val key: Key, initializers: List<CitadelInitializer>) {
    val initializers: List<CitadelInitializer> = Collections.unmodifiableList(ArrayList(initializers))
}

/** Definitions must be registered before loading/acting on their persisted citadels. */
class CitadelDefinitionRegistry {
    private val definitions = LinkedHashMap<Key, CitadelDefinition>()

    fun register(definition: CitadelDefinition) {
        val existing = definitions[definition.key]
        require(existing == null || existing === definition) {
            "Citadel definition ${definition.key} is already registered"
        }
        definitions[definition.key] = definition
    }

    fun find(key: Key): CitadelDefinition? = definitions[key]

    fun require(key: Key): CitadelDefinition = requireNotNull(find(key)) {
        "Citadel definition $key is not registered"
    }
}
