package ru.fornax.fornaxcitadel.paper

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.breaking.DamageBreakPolicy
import ru.fornax.fornaxcitadel.modules.breaking.UnbreakablePolicy
import ru.fornax.fornaxcitadel.modules.fortification.FortificationInitializer
import ru.fornax.fornaxcitadel.modules.fortification.FortifiedHealthBehavior
import ru.fornax.fornaxcitadel.modules.health.MaturingHealthBehavior
import ru.fornax.fornaxcitadel.modules.health.SimpleHealthBehavior
import ru.fornax.fornaxcitadel.modules.ownership.OwnerInitializer
import ru.fornax.fornaxcitadel.modules.ownership.OwnerOrDamagePolicy
import java.util.Locale

object PaperCitadelTypes {
    val REINFORCED = Key("fornaxcitadel", "reinforced")
    val MATURING = Key("fornaxcitadel", "maturing")
    val DEV = Key("fornaxcitadel", "dev")
    val INVULNERABLE = Key("fornaxcitadel", "invulnerable")
    private val byName = listOf(REINFORCED, MATURING, DEV, INVULNERABLE).associateBy { it.key }
    val names: List<String> = byName.keys.toList()

    fun find(alias: String): Key? = byName[alias.lowercase(Locale.ROOT)]
    fun name(key: Key): String? = key.key.takeIf { byName[it] == key }
    fun requiresAdmin(key: Key): Boolean = key == DEV || key == INVULNERABLE

    fun register(module: CitadelModule, settings: PaperSettings) {
        module.registerDefinition(
            CitadelDefinition(REINFORCED, listOf(OwnerInitializer(), FortificationInitializer(1))),
            OwnerOrDamagePolicy, FortifiedHealthBehavior(settings.fortifiedMaxHealth)
        )
        module.registerDefinition(
            CitadelDefinition(MATURING, listOf(OwnerInitializer())), OwnerOrDamagePolicy,
            MaturingHealthBehavior(settings.initialMaxHealth, settings.matureMaxHealth, settings.maturationDurationMillis)
        )
        module.registerDefinition(CitadelDefinition(DEV, emptyList()), DamageBreakPolicy, SimpleHealthBehavior(settings.devMaxHealth))
        module.registerDefinition(CitadelDefinition(INVULNERABLE, emptyList()), UnbreakablePolicy)
    }
}
