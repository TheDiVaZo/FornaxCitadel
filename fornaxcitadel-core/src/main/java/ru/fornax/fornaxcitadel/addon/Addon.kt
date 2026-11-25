package ru.fornax.fornaxcitadel.addon

/** Attaches and detaches an addon's runtime behavior on the owning game thread. */
interface Addon {
    fun onEnable()
    fun onDisable()
}
