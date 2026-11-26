package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.ReadableHandle
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.Collections

/** Registered once at startup; initializer instances may be shared by all citadels of this kind. */
class CitadelDefinition @JvmOverloads constructor(
    val key: Key,
    initializers: List<CitadelInitializer>,
    val level: Int? = null,
    val itemKey: Key? = null
) : CitadelInitializer {
    val initializers: List<CitadelInitializer> = Collections.unmodifiableList(ArrayList(initializers))

    init {
        requireFullKey(key)
        itemKey?.let(::requireFullKey)
        require(level == null || level > 0) { "Citadel level must be positive" }
    }

    override fun initialize(draft: CitadelDraft) {
        draft.addComponent(CitadelKind::class.java, CitadelKind(key))
        for (initializer in initializers) initializer.initialize(draft)
    }

    override fun update(handle: CitadelHandle) {
        handle.putComponent(CitadelKind::class.java, CitadelKind(key))
        for (initializer in initializers) initializer.update(handle)
    }
}

/** Definitions must be registered before loading/acting on their persisted citadels. */
class CitadelDefinitionRegistry {
    private val definitions = LinkedHashMap<Key, CitadelDefinition>()
    private val byItem = mutableMapOf<Key, CitadelDefinition>()

    val keys: List<Key> get() = definitions.keys.toList()

    fun register(definition: CitadelDefinition) {
        val existing = definitions[definition.key]
        require(existing == null || existing === definition) {
            "Citadel definition ${definition.key} is already registered"
        }
        definition.itemKey?.let { bindItem(it, definition) }
        definitions[definition.key] = definition
    }

    fun find(key: Key): CitadelDefinition? = definitions[key]

    fun find(handle: ReadableHandle): CitadelDefinition? =
        handle.getComponent(CitadelKind::class.java)?.definitionKey?.let(definitions::get)

    fun itemKey(key: Key): Key? = find(key)?.itemKey
    fun itemKey(handle: ReadableHandle): Key? = find(handle)?.itemKey
    fun level(key: Key): Int? = find(key)?.level
    fun level(handle: ReadableHandle): Int? = find(handle)?.level

    fun findByItem(itemKey: Key): CitadelDefinition? = byItem[itemKey]

    /** Alternate installation items leave the type's repair and refund item unchanged. */
    fun registerItemAlias(itemKey: Key, definitionKey: Key) {
        requireFullKey(itemKey)
        val definition = require(definitionKey)
        require(definition.itemKey != null) { "Citadel definition $definitionKey has no installation item" }
        bindItem(itemKey, definition)
    }

    private fun bindItem(itemKey: Key, definition: CitadelDefinition) {
        val existing = byItem.putIfAbsent(itemKey, definition)
        require(existing == null || existing === definition) {
            "Item $itemKey is already bound to citadel definition ${existing?.key}"
        }
    }

    fun require(key: Key): CitadelDefinition = requireNotNull(find(key)) {
        "Citadel definition $key is not registered"
    }
}

private fun requireFullKey(key: Key) {
    require(key.namespace.isNotEmpty() && key.key.isNotEmpty()) { "Expected a namespace and value in $key" }
}
