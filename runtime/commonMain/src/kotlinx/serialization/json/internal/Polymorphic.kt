/*
 * Copyright 2017-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.serialization.json.internal

import kotlinx.serialization.*
import kotlinx.serialization.internal.*
import kotlinx.serialization.json.*

@Suppress("UNCHECKED_CAST")
internal inline fun <T> JsonOutput.encodePolymorphically(serializer: SerializationStrategy<T>, value: T, ifPolymorphic: () -> Unit) {
    if (serializer !is AbstractPolymorphicSerializer<*> || json.configuration.useArrayPolymorphism) {
        serializer.serialize(this, value)
        return
    }
    serializer as AbstractPolymorphicSerializer<Any> // PolymorphicSerializer <*> projects 2nd argument of findPolymorphic... to Nothing, so we need an additional cast
    val actualSerializer = serializer.findPolymorphicSerializer(this, value as Any).cast<Any>()
    validateIfSealed(serializer, actualSerializer, json.configuration.classDiscriminator)
    val kind = actualSerializer.descriptor.kind
    checkKind(kind)
    ifPolymorphic()
    actualSerializer.serialize(this, value)
}

private fun validateIfSealed(
    serializer: KSerializer<*>,
    actualSerializer: KSerializer<Any>,
    classDiscriminator: String
) {
    if (serializer !is SealedClassSerializer<*>) return
    if (classDiscriminator in actualSerializer.descriptor.cachedSerialNames()) {
        val baseName = serializer.descriptor.serialName
        val actualName = actualSerializer.descriptor.serialName
        error(
            "Sealed class '$actualName' cannot be serialized as base class '$baseName' because" +
                    " it has property name that conflicts with JSON class discriminator '$classDiscriminator'. " +
                    "You can either change class discriminator in JsonConfiguration, " +
                    "rename property with @SerialName annotation or fall back to array polymorphism"
        )
    }
}

internal fun checkKind(kind: SerialKind) {
    if (kind is UnionKind.ENUM_KIND) error("Enums cannot be serialized polymorphically with 'type' parameter. You can use 'JsonConfiguration.useArrayPolymorphism' instead")
    if (kind is PrimitiveKind) error("Primitives cannot be serialized polymorphically with 'type' parameter. You can use 'JsonConfiguration.useArrayPolymorphism' instead")
    if (kind is PolymorphicKind) error("Actual serializer for polymorphic cannot be polymorphic itself")
}

internal fun <T> JsonInput.decodeSerializableValuePolymorphic(deserializer: DeserializationStrategy<T>): T {
    if (deserializer !is AbstractPolymorphicSerializer<*> || json.configuration.useArrayPolymorphism) {
        return deserializer.deserialize(this)
    }

    val jsonTree = cast<JsonObject>(decodeJson())
    val type = jsonTree.getValue(json.configuration.classDiscriminator).content
    (jsonTree.content as MutableMap).remove(json.configuration.classDiscriminator)
    val actualSerializer = deserializer.findPolymorphicSerializer(this, type).cast<T>()
    return json.readJson(jsonTree, actualSerializer)
}
