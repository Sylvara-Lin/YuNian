package com.yunian.ai.domain.timeline

import java.util.concurrent.ConcurrentHashMap

interface TimelinePayloadCodec {
    val kind: TimelineEventKind
    fun serialize(payload: TimelinePayload): String
    fun deserialize(raw: String): TimelinePayload
}

class PlainTextReasoningCodec : TimelinePayloadCodec {
    override val kind: TimelineEventKind = TimelineEventKind.REASONING

    override fun serialize(payload: TimelinePayload): String {
        val p = payload as? ReasoningPayload
            ?: error("PlainTextReasoningCodec expects ReasoningPayload")

        return p.text
    }

    override fun deserialize(raw: String): TimelinePayload =
        ReasoningPayload(text = raw, durationMs = null)
}

class PlainTextAssistantCodec : TimelinePayloadCodec {
    override val kind: TimelineEventKind = TimelineEventKind.ASSISTANT_TEXT

    override fun serialize(payload: TimelinePayload): String {
        val p = payload as? AssistantTextPayload
            ?: error("PlainTextAssistantCodec expects AssistantTextPayload")
        return p.text
    }

    override fun deserialize(raw: String): TimelinePayload =
        AssistantTextPayload(text = raw, segmentIndex = 0)
}

object TimelinePayloadCodecRegistry {
    private val codecs = ConcurrentHashMap<TimelineEventKind, TimelinePayloadCodec>()

    fun register(codec: TimelinePayloadCodec) {
        codecs[codec.kind] = codec
    }

    fun unregister(kind: TimelineEventKind) {
        codecs.remove(kind)
    }

    fun get(kind: TimelineEventKind): TimelinePayloadCodec? = codecs[kind]

    fun require(kind: TimelineEventKind): TimelinePayloadCodec =
        codecs[kind] ?: error("No TimelinePayloadCodec registered for $kind")

    fun serialize(payload: TimelinePayload): String =
        require(payload.kind).serialize(payload)

    fun deserialize(kind: TimelineEventKind, raw: String): TimelinePayload =
        require(kind).deserialize(raw)

    fun registerBuiltins() {
        register(PlainTextReasoningCodec())
        register(PlainTextAssistantCodec())
    }

    fun clear() {
        codecs.clear()
    }
}
