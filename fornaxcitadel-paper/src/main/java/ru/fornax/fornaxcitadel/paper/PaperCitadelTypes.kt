package ru.fornax.fornaxcitadel.paper

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.breaking.DamageBreakPolicy
import ru.fornax.fornaxcitadel.modules.breaking.UnbreakablePolicy
import java.util.Locale

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

    fun isUnbreakable(key: Key): Boolean = name(key)?.let { configured.getValue(it).breakPolicy } ==
        CitadelBreakPolicy.UNBREAKABLE
    fun requiresAdmin(key: Key): Boolean = isUnbreakable(key)

    fun register(module: CitadelModule) {
        for ((name, settings) in configured) {
            val policy = when (settings.breakPolicy) {
                CitadelBreakPolicy.DAMAGE -> DamageBreakPolicy
                CitadelBreakPolicy.UNBREAKABLE -> UnbreakablePolicy
            }
            module.registerDefinition(
                CitadelDefinition(byName.getValue(name), emptyList()),
                policy,
                settings.health?.createBehavior()
            )
        }
    }

    companion object {
        val REINFORCED = Key("fornaxcitadel", "reinforced")
        val MATURING = Key("fornaxcitadel", "maturing")
        val INVULNERABLE = Key("fornaxcitadel", "invulnerable")
    }
}
