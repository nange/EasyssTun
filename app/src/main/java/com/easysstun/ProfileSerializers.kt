package com.easysstun

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Codec for a numeric profile field that older builds stored as a JSON string.
 *
 * Builds before the typed model wrote `"serverPort": "443"`, `"socksPort": "4080"`
 * and `"timeout": ""`; the typed model writes numbers. Decoding accepts both, so
 * an existing profile store keeps working after the upgrade, and normalizes the
 * value on the next write:
 *
 *  - a JSON number is taken as is;
 *  - a string is trimmed and parsed (`""` or `"abc"` becomes `null`);
 *  - JSON `null` or an unexpected element becomes `null`.
 *
 * `null` means "not configured" and makes the native config fall back to its own
 * default, which is exactly what a blank legacy value used to do.
 */
internal object LenientIntSerializer : KSerializer<Int?> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.easysstun.LenientInt", PrimitiveKind.INT).nullable

    override fun deserialize(decoder: Decoder): Int? {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("LenientIntSerializer requires a JSON decoder")
        val element = input.decodeJsonElement()
        return when {
            element is JsonNull -> null
            element is JsonPrimitive && element.isString -> element.content.trim().toIntOrNull()
            element is JsonPrimitive -> element.intOrNull
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: Int?) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("LenientIntSerializer requires a JSON encoder")
        if (value == null) output.encodeJsonElement(JsonNull) else output.encodeInt(value)
    }
}

/**
 * Codec for a boolean profile flag that older builds stored as a string.
 *
 * The legacy representation was the exact text "true"/"false" (anything else
 * meant false, matching the old `parseBooleanFlag`). Decoding accepts a JSON
 * boolean or that string form; writing always produces a real JSON boolean.
 */
internal object LenientBooleanSerializer : KSerializer<Boolean> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.easysstun.LenientBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("LenientBooleanSerializer requires a JSON decoder")
        val element = input.decodeJsonElement()
        return when {
            element is JsonPrimitive && element.isString ->
                element.content.equals("true", ignoreCase = true)
            element is JsonPrimitive -> element.booleanOrNull ?: false
            else -> false
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("LenientBooleanSerializer requires a JSON encoder")
        output.encodeBoolean(value)
    }
}
