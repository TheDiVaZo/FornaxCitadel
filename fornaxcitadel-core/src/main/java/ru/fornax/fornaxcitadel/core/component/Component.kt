package ru.fornax.fornaxcitadel.core.component

import ru.fornax.fornaxcitadel.core.serialization.ReadAndWriteAble

// The component must be serializable to be implemented
abstract class Component: ReadAndWriteAble() {
}