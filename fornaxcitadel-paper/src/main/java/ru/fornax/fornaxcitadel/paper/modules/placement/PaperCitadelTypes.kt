package ru.fornax.fornaxcitadel.paper.modules.placement

import ru.fornax.fornaxcitadel.core.util.Key

/** Stable identifiers for built-in types. The active definitions are available through FornaxCitadelAPI. */
class PaperCitadelTypes private constructor() {
    companion object {
        val REINFORCED = Key("fornaxcitadel", "reinforced")
        val MATURING = Key("fornaxcitadel", "maturing")
        val INVULNERABLE = Key("fornaxcitadel", "invulnerable")
    }
}
