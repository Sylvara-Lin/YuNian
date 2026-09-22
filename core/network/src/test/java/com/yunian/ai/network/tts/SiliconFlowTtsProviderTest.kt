package com.yunian.ai.network.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiliconFlowTtsProviderTest {

    private val realResponse = """
        {
          "result": [
            {
              "model": "FunAudioLLM/CosyVoice2-0.5B",
              "customName": "dp_42824",
              "text": "指挥家，可以占用你一些时间吗",
              "uri": "speech:dp_42824:d60c969719ns73f85u70:itkuhmysngpxdtoldgww"
            },
            {
              "model": "FunAudioLLM/CosyVoice2-0.5B",
              "customName": "dp_34249",
              "text": "test",
              "uri": "speech:dp_34249:d60c969719ns73f85u70:gwxelyiwjzmnptsdmrwf"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun realResultKey_resolvesByCustomName() {
        assertEquals(
            "speech:dp_42824:d60c969719ns73f85u70:itkuhmysngpxdtoldgww",
            SiliconFlowTtsProvider.resolveVoiceUriFromJson(realResponse, "dp_42824")
        )
    }

    @Test
    fun realResultKey_resolvesByUri() {
        assertEquals(
            "speech:dp_34249:d60c969719ns73f85u70:gwxelyiwjzmnptsdmrwf",
            SiliconFlowTtsProvider.resolveVoiceUriFromJson(
                realResponse,
                "speech:dp_34249:d60c969719ns73f85u70:gwxelyiwjzmnptsdmrwf"
            )
        )
    }

    @Test
    fun nameNotFound_returnsNull() {
        assertNull(SiliconFlowTtsProvider.resolveVoiceUriFromJson(realResponse, "dp_99999"))
    }

    @Test
    fun legacyResultsKey_stillResolves() {

        val legacy = """{"results": [{"customName":"dp_42824","uri":"speech:legacy:xxx","text":"t","model":"m"}]}"""
        assertEquals(
            "speech:legacy:xxx",
            SiliconFlowTtsProvider.resolveVoiceUriFromJson(legacy, "dp_42824")
        )
    }

    @Test
    fun malformedJson_returnsNull() {
        assertNull(SiliconFlowTtsProvider.resolveVoiceUriFromJson("not json", "dp_42824"))
    }

    @Test
    fun emptyUri_returnsNull() {
        val empty = """{"result":[{"customName":"dp_x","uri":"","text":"t","model":"m"}]}"""
        assertNull(SiliconFlowTtsProvider.resolveVoiceUriFromJson(empty, "dp_x"))
    }
}
