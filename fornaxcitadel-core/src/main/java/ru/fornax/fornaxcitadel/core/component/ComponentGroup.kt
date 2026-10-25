package ru.fornax.fornaxcitadel.core.component

data class ComponentGroup(
    val name: String?,
    val mask: Long,
    val id: Short
) {

    fun match(bits: Long): Boolean {
        return mask == bits
    }

    fun containsAny(bits: Long): Boolean {
        return (bits and mask) != 0L
    }

    fun containsAll(bits: Long): Boolean {
        return mask == (mask and bits)
    }
}
