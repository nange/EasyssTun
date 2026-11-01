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

/** 旧版本把数值字段存成 JSON 字符串（`"443"`、`"4080"`、`""`），这里两种表示都接受，写入时归一化为数字。
 * 字符串 trim 后解析，空串/非法值、JSON `null`、意外元素一律读作 `null`；
 * `null` 表示未配置，由 native 侧自身默认值兜底——正是旧版空值原本的效果。 */
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

/** 旧版本把布尔存成字符串 `"true"`/`"false"`（其余一律 false，与原 `parseBooleanFlag` 一致）；
 * 解码同时接受 JSON 布尔与该字符串，写入恒为真正的 JSON 布尔。 */
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
