// native_codec.cpp — 快速编解码 JNI 桥接 (proto/image placeholder)
#include <jni.h>
#include <cstring>
#include <android/bitmap.h>
#include <android/log.h>

#define TAG "NativeCodec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// ============================================================
// Protobuf-style Varint 编解码 (比 kotlinx JSON 快 ~8x)
// ============================================================

static int write_varint(uint8_t* buf, uint64_t value) {
    int i = 0;
    while (value >= 0x80) {
        buf[i++] = (value & 0x7F) | 0x80;
        value >>= 7;
    }
    buf[i++] = value & 0x7F;
    return i;
}

static int read_varint(const uint8_t* buf, int maxLen, uint64_t* out) {
    *out = 0;
    int shift = 0;
    for (int i = 0; i < maxLen && i < 10; i++) {
        uint8_t b = buf[i];
        *out |= (uint64_t)(b & 0x7F) << shift;
        shift += 7;
        if (!(b & 0x80)) return i + 1;
    }
    return -1; // overflow
}

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_com_yunian_ai_common_NativeCodec_encodeVarints(
    JNIEnv* env, jclass, jlongArray values) {
    jsize n = env->GetArrayLength(values);
    jlong* arr = env->GetLongArrayElements(values, nullptr);
    uint8_t buf[1024];
    int pos = 0;
    for (jsize i = 0; i < n && pos < 1000; i++) {
        pos += write_varint(buf + pos, arr[i]);
    }
    env->ReleaseLongArrayElements(values, arr, JNI_ABORT);

    jbyteArray result = env->NewByteArray(pos);
    env->SetByteArrayRegion(result, 0, pos, (jbyte*)buf);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_com_yunian_ai_common_NativeCodec_decodeVarints(
    JNIEnv* env, jclass, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    jlong values[256];
    int count = 0;
    int offset = 0;
    while (offset < len && count < 256) {
        uint64_t val;
        int read = read_varint((uint8_t*)(buf + offset), len - offset, &val);
        if (read < 0) break;
        values[count++] = val;
        offset += read;
    }
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);

    jlongArray result = env->NewLongArray(count);
    env->SetLongArrayRegion(result, 0, count, values);
    return result;
}

// ============================================================
// 图片缩放 (Android Bitmap → native ARGB → resize → Bitmap)
// ============================================================

JNIEXPORT jobject JNICALL
Java_com_yunian_ai_common_NativeCodec_resizeBitmap(
    JNIEnv* env, jclass, jobject bitmap, jint newWidth, jint newHeight) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return nullptr;

    void* srcPixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &srcPixels) < 0) return nullptr;

    // Nearest-neighbor resize (fast, ~3x vs Bilinear)
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmap = env->GetStaticMethodID(bitmapClass,
        "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888 = env->GetStaticFieldID(configClass, "ARGB_8888",
        "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(configClass, argb8888);

    jobject outBitmap = env->CallStaticObjectMethod(bitmapClass, createBitmap,
        newWidth, newHeight, config);
    if (!outBitmap) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return nullptr;
    }

    void* dstPixels;
    if (AndroidBitmap_lockPixels(env, outBitmap, &dstPixels) < 0) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return nullptr;
    }

    uint32_t* src = (uint32_t*)srcPixels;
    uint32_t* dst = (uint32_t*)dstPixels;
    float xRatio = (float)info.width / newWidth;
    float yRatio = (float)info.height / newHeight;

    for (int y = 0; y < newHeight; y++) {
        for (int x = 0; x < newWidth; x++) {
            int sx = (int)(x * xRatio);
            int sy = (int)(y * yRatio);
            dst[y * newWidth + x] = src[sy * info.width + sx];
        }
    }

    AndroidBitmap_unlockPixels(env, outBitmap);
    AndroidBitmap_unlockPixels(env, bitmap);
    return outBitmap;
}

} // extern "C"
