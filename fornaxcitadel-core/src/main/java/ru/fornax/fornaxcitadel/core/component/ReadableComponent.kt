package ru.fornax.fornaxcitadel.core.component

/**
 * Read-only access to a live component on its owning game thread.
 * Getters must not change gameplay state, input objects or settings used by the current calculation,
 * including through captured writable references. Callers trust this contract without revalidation.
 */
interface ReadableComponent
