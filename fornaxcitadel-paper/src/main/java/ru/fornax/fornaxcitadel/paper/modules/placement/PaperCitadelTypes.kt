package ru.fornax.fornaxcitadel.paper.modules.placement

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.facade.ReadableHandle
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.HealthInitializer
import ru.fornax.fornaxcitadel.modules.health.HealthDefinition
import ru.fornax.fornaxcitadel.modules.health.HealthService
import ru.fornax.fornaxcitadel.modules.health.MaturationDefinition
import ru.fornax.fornaxcitadel.modules.health.MaturingInitializer
import java.time.Duration
import java.util.Locale
import kotlin.collections.iterator

/** One immutable catalog shared by definitions, installation items, permissions and commands. */
class PaperCitadelTypes(settings: CitadelTypesSettings = CitadelTypesSettings()) {
    private val configured = settings.types.toMap()
    private val byName = configured.keys.associateWith { Key("fornaxcitadel", it) }
    val names: List<String> = byName.keys.toList()
    val keys: List<Key> = byName.values.toList()

    fun find(alias: String): Key? = byName[alias.lowercase(Locale.ROOT)]
    fun name(key: Key): String? = key.key.takeIf { byName[it] == key }

    /** Installation material identity is independent of the citadel definition's identity. */
    fun itemKey(key: Key): Key? = name(key)?.let { configured.getValue(it).item }

    fun level(key: Key): Int? = name(key)?.let { configured.getValue(it).lvl }

    fun itemKey(citadel: ReadableHandle): Key? = citadel.getComponent(CitadelKind::class.java)?.definitionKey?.let(::itemKey)

    fun level(citadel: ReadableHandle): Int? = citadel.getComponent(CitadelKind::class.java)?.definitionKey?.let(::level)

    fun damagePolicy(key: Key): CitadelDamagePolicy? = name(key)?.let { configured.getValue(it).damagePolicy }

    fun isInvulnerable(key: Key): Boolean = damagePolicy(key) == CitadelDamagePolicy.DENY
    fun requiresAdmin(key: Key): Boolean = isInvulnerable(key)

    fun register(definitions: CitadelDefinitionRegistry, health: HealthService) {
        for ((name, settings) in configured) {
            val key = byName.getValue(name)
            val initializers = buildList<CitadelInitializer> {
                add(HealthInitializer(health))
                add(MaturingInitializer())
            }
            definitions.register(CitadelDefinition(key, initializers))
            settings.health?.let { health.registerDefinition(key, HealthDefinition(it.max)) }
            health.maturation.registerDefinition(key, MaturationDefinition(
                Duration.ofSeconds(settings.maturing?.durationSeconds ?: 0),
                settings.maturing?.initialDamageMultiplier ?: 1.0
            ))
        }
    }

    companion object {
        val REINFORCED = Key("fornaxcitadel", "reinforced")
        val MATURING = Key("fornaxcitadel", "maturing")
        val INVULNERABLE = Key("fornaxcitadel", "invulnerable")
    }
}
