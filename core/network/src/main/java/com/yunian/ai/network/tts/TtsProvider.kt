package com.yunian.ai.network.tts

enum class TtsProvider(val displayName: String, val description: String) {

    ALIYUN("阿里云", "阿里云语音合成 - 多种音色可选"),
    BAIDU("百度", "百度语音合成 - 中文效果好"),
    XUNFEI("讯飞", "讯飞语音 - 情感丰富"),
    MICROSOFT("微软Azure", "Azure TTS - 多语言神经语音"),
    VOLCENGINE("火山引擎", "豆包语音合成大模型 - 高拟真音色"),
    SILICONFLOW("硅基流动", "CosyVoice2 高拟真语音合成，支持自定义音色"),
    MIMO("小米 MiMo", "MiMo V2.5 TTS（chat/completions + audio）"),
    OPENAI_COMPAT("自定义 OpenAI", "OpenAI /v1/audio/speech 兼容接口（NewAPI、自建网关等）"),
    SHERPA_LOCAL("本地离线", "sherpa-onnx 端上 TTS，无需联网，首次需下载模型")
}
