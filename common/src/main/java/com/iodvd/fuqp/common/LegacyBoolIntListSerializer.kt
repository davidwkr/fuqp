package com.iodvd.fuqp.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Tolerant serializer for a `List<Int>` field that used to be a `Boolean` in older configs.
 * A legacy boolean decodes to an empty list (the new default), anything else decodes normally,
 * and it always *serializes* as a list — so the first save after an upgrade self-heals the file.
 *
 * Without this, one stale boolean value makes the whole config fail to parse, and the service
 * silently falls back to an empty config (protecting nothing). See the 2026-10-26 incident where
 * three hand-edited proxy entries carried `restrictedZygotePermissions: false`.
 */
object LegacyBoolIntListSerializer : KSerializer<List<Int>> {
    private val delegate = ListSerializer(Int.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<Int>) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<Int> {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val element = jsonDecoder.decodeJsonElement()
        // Legacy boolean form -> treat as "no restrictions".
        if (element is JsonPrimitive && element.booleanOrNull != null) return emptyList()
        return jsonDecoder.json.decodeFromJsonElement(delegate, element)
    }
}
