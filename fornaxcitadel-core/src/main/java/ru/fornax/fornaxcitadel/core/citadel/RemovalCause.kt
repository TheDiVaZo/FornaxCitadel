package ru.fornax.fornaxcitadel.core.citadel

/** Describes the completed operation; a cause is not an authorization token. */
enum class RemovalCause { OWNER_DISMANTLE, DESTROYED, ADMIN, WORLD_EDIT }
