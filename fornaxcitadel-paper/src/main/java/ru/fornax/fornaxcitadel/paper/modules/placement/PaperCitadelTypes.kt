package ru.fornax.fornaxcitadel.paper.modules.placement

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.health.DamagePolicy
import ru.fornax.fornaxcitadel.modules.health.HealthInitializer
import ru.fornax.fornaxcitadel.modules.health.MaturingInitializer
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

    fun isInvulnerable(key: Key): Boolean = name(key)?.let { configured.getValue(it).damagePolicy } ==
        CitadelDamagePolicy.DENY
    fun requiresAdmin(key: Key): Boolean = isInvulnerable(key)

    fun register(module: CitadelModule) {
        for ((name, settings) in configured) {
            val policy = when (settings.damagePolicy) {
                CitadelDamagePolicy.ALLOW -> DamagePolicy.ALLOW
                CitadelDamagePolicy.DENY -> DamagePolicy.DENY
            }
            val initializers = buildList<CitadelInitializer> {
                settings.health?.let { add(HealthInitializer(it.max)) }
                settings.maturing?.let {
                    add(MaturingInitializer(Math.multiplyExact(it.durationSeconds, 1000), it.initialDamageMultiplier))
                }
            }
            module.registerDefinition(CitadelDefinition(byName.getValue(name), initializers), policy)
        }
    }

    companion object {
        val REINFORCED = Key("fornaxcitadel", "reinforced")
        val MATURING = Key("fornaxcitadel", "maturing")
        val INVULNERABLE = Key("fornaxcitadel", "invulnerable")
    }
}
