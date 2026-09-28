package com.yunian.ai.common

import android.graphics.Bitmap

object NativeCodec {

    init {
        try { System.loadLibrary("lianyu_security") }
        catch (e: UnsatisfiedLinkError) {  }
    }

    @JvmStatic external fun encodeVarints(values: LongArray): ByteArray

    @JvmStatic external fun decodeVarints(data: ByteArray): LongArray

    @JvmStatic external fun resizeBitmap(bitmap: Bitmap, newWidth: Int, newHeight: Int): Bitmap?
}
