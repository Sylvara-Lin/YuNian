package com.yunian.ai.network

import okhttp3.CertificatePinner

object CertificatePins {

    private const val SUFLOW_PIN = "sha256/Nh9PzSv3Z/jvrTTdRgBJWEp2CkPjcSHBtzZ2O8Nkmgs="

    val certificatePinner: CertificatePinner = CertificatePinner.Builder()
        .add("suflow.cloud", SUFLOW_PIN)
        .build()
}
