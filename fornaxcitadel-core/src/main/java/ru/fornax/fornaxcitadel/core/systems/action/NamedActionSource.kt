package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.util.Key

/** An immutable origin label for actions that need no additional source details. */
data class NamedActionSource(override val key: Key) : ActionSource
