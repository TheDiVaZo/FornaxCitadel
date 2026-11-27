package ru.fornax.fornaxcitadel.core.systems.actio

/** Source types are explicit API keys, even when a platform supplies a subclass at runtime. */
class DispatcherRegistry {
    private val dispatchers = mutableMapOf<Class<*>, Dispatcher<*>>()

    fun <S : Any> register(sourceType: Class<S>, dispatcher: Dispatcher<S>) {
        val existing = dispatchers.putIfAbsent(sourceType, dispatcher)
        require(existing == null || existing === dispatcher) {
            "A dispatcher is already registered for ${sourceType.name}"
        }
    }

    /** Registration binds each class token to a dispatcher of the same source type. */
    @Suppress("UNCHECKED_CAST")
    operator fun <S : Any> get(sourceType: Class<S>): Dispatcher<S>? =
        dispatchers[sourceType] as Dispatcher<S>?

    fun <S : Any> unregister(sourceType: Class<S>, dispatcher: Dispatcher<S>): Boolean =
        dispatchers.remove(sourceType, dispatcher)
}
