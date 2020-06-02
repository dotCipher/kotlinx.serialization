/*
 * Copyright 2017-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.serialization.json

import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.json.internal.*
import kotlinx.serialization.modules.*
import kotlinx.serialization.test.*
import kotlin.test.*

abstract class JsonTestBase {
    protected val default = Json(JsonConfiguration.Default)
    protected val unquoted = Json { unquotedPrint = true }
    protected val unquotedLenient = Json { unquotedPrint = true; isLenient = true; ignoreUnknownKeys = true; serializeSpecialFloatingPointValues = true }
    protected val lenient = Json { isLenient = true; ignoreUnknownKeys = true; serializeSpecialFloatingPointValues = true }

    internal inline fun <reified T : Any> Json.stringify(value: T, useStreaming: Boolean): String {
        val serializer = context.getContextualOrDefault<T>()
        return stringify(serializer, value, useStreaming)
    }

    internal fun <T> Json.stringify(serializer: SerializationStrategy<T>, value: T, useStreaming: Boolean): String {
        return if (useStreaming) {
            stringify(serializer, value)
        } else {
            val tree = writeJson(value, serializer)
            // kotlinx.serialization/issues/277
            stringify(JsonElementSerializer, tree)
        }
    }

    inline fun <reified T : Any> Json.stringify(list: List<T>, useStreaming: Boolean): String {
        return if (useStreaming) {
            // Overload to test public list extension
            stringify(list)
        } else {
            stringify(context.getContextualOrDefault<T>().list, list)
        }
    }

    inline fun <reified K : Any, reified V : Any> Json.stringify(map: Map<K, V>, useStreaming: Boolean): String {
        return if (useStreaming) {
            // Overload to test public map extension
            stringify(map)
        } else {
            stringify(MapSerializer(context.getContextualOrDefault<K>(), context.getContextualOrDefault<V>()), map)
        }
    }

    internal inline fun <reified T : Any> Json.parse(source: String, useStreaming: Boolean): T {
        val deserializer = context.getContextualOrDefault<T>()
        return parse(deserializer, source, useStreaming)
    }

    internal fun <T> Json.parse(deserializer: DeserializationStrategy<T>, source: String, useStreaming: Boolean): T {
        return if (useStreaming) {
            parse(deserializer, source)
        } else {
            val parser = JsonReader(source)
            val input = StreamingJsonInput(this, WriteMode.OBJ, parser)
            val tree = input.decodeJson()
            if (!input.reader.isDone) { error("Reader has not consumed the whole input: ${input.reader}") }
            readJson(tree, deserializer)
        }
    }

    internal inline fun <reified T : Any> Json.parseList(content: String, useStreaming: Boolean): List<T> {
        return if (useStreaming) {
            // Overload to test public list extension
            parseList(content)
        } else {
            parse(context.getContextualOrDefault<T>().list, content, useStreaming)
        }
    }

    internal inline fun <reified K : Any, reified V : Any> Json.parseMap(
        content: String,
        useStreaming: Boolean
    ): Map<K, V> {
        return if (useStreaming) {
            // Overload to test public map extension
            parseMap(content)
        } else {
            parse(MapSerializer(context.getContextualOrDefault<K>(), context.getContextualOrDefault<V>()), content, useStreaming)
        }
    }

    protected open fun parametrizedTest(test: (Boolean) -> Unit) {
        val streamingResult = runCatching { test(true) }
        val treeResult = runCatching { test(false) }
        processResults(streamingResult, treeResult)
    }

    private inner class DualFormat(
        val json: Json,
        val useStreaming: Boolean,
        override val context: SerialModule = EmptyModule
    ) : StringFormat {
        override fun <T> stringify(serializer: SerializationStrategy<T>, value: T): String {
            return json.stringify(serializer, value, useStreaming)
        }

        override fun <T> parse(deserializer: DeserializationStrategy<T>, string: String): T {
            return json.parse(deserializer, string, useStreaming)
        }
    }

    protected fun parametrizedTest(json: Json, test: StringFormat.(StringFormat) -> Unit) {
        val streamingResult = runCatching { json.test(DualFormat(json, true)) }
        val treeResult = runCatching { json.test(DualFormat(json, false)) }
        processResults(streamingResult, treeResult)
    }

    protected fun processResults(streamingResult: Result<*>, treeResult: Result<*>) {
        val results = listOf(streamingResult, treeResult)
        results.forEachIndexed { _, result ->
            result.onFailure { throw it }
        }
        assertEquals(streamingResult.getOrNull()!!, treeResult.getOrNull()!!)
    }

    /**
     * Same as [assertStringFormAndRestored], but tests both json converters (streaming and tree)
     * via [parametrizedTest]
     */
    internal fun <T> assertJsonFormAndRestored(
        serializer: KSerializer<T>,
        data: T,
        expected: String,
        json: Json = default
    ) {
        parametrizedTest { useStreaming ->
            val serialized = json.stringify(serializer, data, useStreaming)
            assertEquals(expected, serialized)
            val deserialized: T = json.parse(serializer, serialized, useStreaming)
            assertEquals(data, deserialized)
        }
    }

    inline fun <reified T : Throwable> assertFailsWithMessage(message: String, block: () -> Unit) {
        val exception = assertFailsWith(T::class, null, block)
        assertTrue(exception.message!!.contains(message), "Expected message '${exception.message}' to contain substring '$message'")
    }
}
