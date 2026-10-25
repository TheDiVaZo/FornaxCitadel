package ru.fornax.fornaxcitadel.core.util

import java.util.Comparator
import java.util.OptionalInt

data class Key(
    val namespace: String,
    val key: String
) : Comparable<Key> {
    init {
        validatePart(
            "namespace",
            namespace,
            namespace,
            key,
            checkNamespace(namespace),
            NAMESPACE_PATTERN
        )
        validatePart(
            "key",
            key,
            namespace,
            key,
            checkValue(key),
            VALUE_PATTERN
        )
    }

    val value: String
        get() = key

    fun asString(): String {
        return "$namespace$DEFAULT_SEPARATOR$key"
    }

    override fun compareTo(other: Key): Int {
        return KEY_COMPARATOR.compare(this, other)
    }

    override fun toString(): String {
        return asString()
    }

    companion object {
        const val DEFAULT_SEPARATOR: Char = ':'

        private const val NAMESPACE_PATTERN = "[a-z0-9_.-]"
        private const val VALUE_PATTERN = "[a-z0-9/._-]"

        private val KEY_COMPARATOR = compareBy<Key>({ it.key }, { it.namespace })

        @JvmStatic
        fun key(string: String): Key {
            return key(string, DEFAULT_SEPARATOR)
        }

        @JvmStatic
        fun key(string: String, separator: Char): Key {
            val separatorIndex = string.indexOf(separator)
            val namespace = string.substring(0, separatorIndex)
            if (namespace.isEmpty() or namespace.isBlank()) throw IllegalArgumentException("namespace must not be blank")
            val key = string.substring(separatorIndex + 1)
            return Key(namespace, key)
        }

        @JvmStatic
        fun key(namespace: String, key: String): Key {
            return Key(namespace, key)
        }

        @JvmStatic
        fun comparator(): Comparator<Key> {
            return KEY_COMPARATOR
        }

        @JvmStatic
        fun parseable(string: String?): Boolean {
            if (string == null) return false

            val separatorIndex = string.indexOf(DEFAULT_SEPARATOR)
            val namespace = string.substring(0, separatorIndex)
            if (namespace.isEmpty() or namespace.isBlank()) throw IllegalArgumentException("namespace must not be blank")
            val key = string.substring(separatorIndex + 1)
            return parseableNamespace(namespace) && parseableValue(key)
        }

        @JvmStatic
        fun parseableNamespace(namespace: String): Boolean {
            return checkNamespace(namespace).isEmpty
        }

        @JvmStatic
        fun checkNamespace(namespace: String): OptionalInt {
            if (namespace == "..") return OptionalInt.of(-1)

            for (index in namespace.indices) {
                if (!allowedInNamespace(namespace[index])) {
                    return OptionalInt.of(index)
                }
            }
            return OptionalInt.empty()
        }

        @JvmStatic
        fun parseableValue(value: String): Boolean {
            return checkValue(value).isEmpty
        }

        @JvmStatic
        fun parseableKey(key: String): Boolean {
            return parseableValue(key)
        }

        @JvmStatic
        fun checkValue(value: String): OptionalInt {
            for (index in value.indices) {
                if (!allowedInValue(value[index])) {
                    return OptionalInt.of(index)
                }
            }
            return OptionalInt.empty()
        }

        @JvmStatic
        fun checkKey(key: String): OptionalInt {
            return checkValue(key)
        }

        @JvmStatic
        fun allowedInNamespace(character: Char): Boolean {
            return character == '_' ||
                character == '-' ||
                character in 'a'..'z' ||
                character in '0'..'9' ||
                character == '.'
        }

        @JvmStatic
        fun allowedInValue(character: Char): Boolean {
            return allowedInNamespace(character) || character == '/'
        }

        @JvmStatic
        fun allowedInKey(character: Char): Boolean {
            return allowedInValue(character)
        }

        private fun validatePart(
            partName: String,
            part: String,
            namespace: String,
            key: String,
            invalidIndex: OptionalInt,
            pattern: String
        ) {
            if (invalidIndex.isEmpty) return

            val index = invalidIndex.asInt
            val message = if (index == -1) {
                "'$part' is not a valid value for $partName"
            } else {
                "Invalid $pattern character '${part[index]}' in $partName " +
                    "of Key[$namespace$DEFAULT_SEPARATOR$key] at index $index"
            }
            throw InvalidKeyException(namespace, key, message)
        }
    }
}
