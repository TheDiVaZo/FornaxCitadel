package ru.fornax.fornaxcitadel.core.component

data class ComponentGroup(
    var name: String? = null,
    val mask: Long,
    val id: Short
) {

    fun match(bits: Long): Boolean {
        return mask == bits
    }

    fun containsAny(bits: Long): Boolean {
        return (bits and mask) > 0
    }

    fun containsAll(bits: Long): Boolean {
        return mask == (mask and bits)
    }
}