# ================================================================
# YuNian ProGuard/R8 Rules — Production Release
# ================================================================

# -overloadaggressively  # REMOVED: breaks Kotlin metadata → R8 produces invalid bytecode → SIGABRT
# -mergeinterfacesaggressively  # REMOVED: aggressive interface merging corrupts class hierarchy → SIGABRT on initWeChat
# -allowaccessmodification      # REMOVED: conservative — interacts badly with Kotlin inline/companion access
-repackageclasses
-renamesourcefileattribute ""
-classobfuscationdictionary proguard-dictionary.txt
-packageobfuscationdictionary proguard-dictionary.txt

# Prevent -repackageclasses from moving Dex2C-whitelisted classes out of
# com.yunian.ai.security — transpiler matches by FQN (com.yunian.ai.security.KmsProvider.decryptWithMetadata)
-keeppackagenames com.yunian.ai.security

# Kotlin metadata: R8 must be able to parse these for correct optimization
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.metadata.**
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Exceptions,Signature,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations

# One-Piece Shell: only keep JNI/manifest entry classes whose names must be stable.
# All other security/* classes are now obfuscated — their names are not visible in DEX strings.
-keep class com.yunian.ai.security.NativeBridge { *; }
-keep class com.yunian.ai.security.KmsProvider { *; }
-keep class com.yunian.ai.security.StaticApkShell { *; }
-keep class com.yunian.ai.security.YuNianShellApplication { *; }
-keep class com.yunian.ai.security.G0 { *; }
-keep class com.yunian.ai.security.SecurityState { *; }
-keep class com.yunian.ai.security.SActivity { *; }
-keep class com.yunian.ai.security.SReceiver { *; }
-keep class com.yunian.ai.security.SService { *; }
-keep class com.yunian.ai.security.VmpDex2cDispatcher { *; }
-keep class com.yunian.ai.security.SecurityOrchestrator { *; }
-keep class com.yunian.ai.security.Sm4Cipher { *; }
-keep class com.yunian.ai.security.CompositeVmpRuntime { *; }
-keep class com.yunian.ai.security.SecurityGuard { *; }
# Thin-shell Java entry reflects these names after InMemoryDexClassLoader merge.
# Kotlin `object` methods are instance methods on INSTANCE (not @JvmStatic).
-keepclassmembers class com.yunian.ai.security.G0 {
    public static final com.yunian.ai.security.G0 INSTANCE;
    public <methods>;
}
-keepclassmembers class com.yunian.ai.security.SecurityState {
    public static final com.yunian.ai.security.SecurityState INSTANCE;
    public <methods>;
}

# P2-15: 阻止 R8 内联 Dex2C 白名单方法，确保转译器能找到字节码
# 使用 <methods> 匹配所有方法（ProGuard 不支持 *** 通配符）
-keepclassmembers class com.yunian.ai.security.KmsProvider {
    <methods>;
}
-keepclassmembers class com.yunian.ai.security.SecurityOrchestrator {
    <methods>;
}
-keepclassmembers class com.yunian.ai.security.Sm4Cipher {
    <methods>;
}
-keepclassmembers class com.yunian.ai.security.CompositeVmpRuntime {
    <methods>;
}
-keepclassmembers class com.yunian.ai.security.SecurityGuard {
    <methods>;
}
-keepclassmembers class com.yunian.ai.security.VmpDex2cDispatcher {
    <methods>;
}

# Strip Android logging in release builds.
# EXCEPTION: SecureLog.critical() uses Log.wtf — keep the class intact.
-keep class com.yunian.ai.common.SecureLog { *; }
-keep class com.yunian.ai.common.RemoteKeyProvider { *; }
-keep class com.yunian.ai.network.CertificatePins { *; }

# 🔒 ServiceRegistry: reflection-based register()/get() — must survive obfuscation
-keep class com.yunian.ai.domain.ServiceRegistry { *; }
-keepclassmembers class com.yunian.ai.domain.ServiceRegistry {
    public static <methods>;
}

# 🔒 feature:notification: CompanionKeepAliveService + BootReceiver declared in AndroidManifest
-keep class com.yunian.ai.feature.notification.** { *; }

# 🔒 Push: PushManager imported in YuNianApplication, routes to vendor Push SDKs
-keep class com.yunian.ai.push.PushManager { *; }

# kotlinx.serialization: preserve serializers for reflection-based adapter lookup
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.yunian.ai.**$$serializer { *; }
-keepclassmembers class com.yunian.ai.** {
    *** Companion;
}
-keepclasseswithmembers class com.yunian.ai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int w(...);
    public static int e(...);
}

# Android manifest entry points must stay loadable by class name.
# R8 updates manifest class references when obfuscation renames them.
-keep class com.yunian.ai.YuNianApplication { *; }
-keep class com.yunian.ai.MainActivity { *; }
-keep class com.yunian.ai.security.NativeBridge { *; }
-keep,allowobfuscation class * extends android.app.Service { *; }
-keep,allowobfuscation class * extends android.content.BroadcastReceiver { *; }
-keep,allowobfuscation class * extends androidx.work.Worker { *; }
-keep,allowobfuscation class * extends androidx.work.CoroutineWorker { *; }

# JNI entry points: NativeBridge is registered by native code via
# FindClass("com/yunian/ai/security/NativeBridge") + RegisterNatives, and
# KmsProvider uses Java_com_yunian_ai_security_KmsProvider_* exported JNI
# symbols. Keep class names, member names, and descriptors intact.
-keep,includedescriptorclasses class com.yunian.ai.security.NativeBridge { *; }
-keep,includedescriptorclasses class com.yunian.ai.security.KmsProvider { *; }

# 说明：Google AI Edge LiteRT-LM 相关 keep 规则已随 feature:localmodel 一并删除
# （D4 本地推理统一由 core:agent 的 Rust Cordis Agent / liblianyu_agent.so 承担）。

# Prevent R8 from stripping System.loadLibrary calls in static initializers.
# Even with -keep, R8 may optimize away side-effect-free-looking code paths
# that include loadLibrary. This directive preserves all native method stubs.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ================================================================
# sherpa-onnx (com.k2fsa.sherpa.onnx) — CRITICAL JNI FIX
# ================================================================
# AAR ships an EMPTY proguard.txt (no consumer rules), so R8 was renaming
# config data classes (EndpointConfig/FeatureConfig/OnlineModelConfig/...),
# but libsherpa-onnx-jni.so resolves them by HARD-CODED string:
#   FindClass("com/k2fsa/sherpa/onnx/OnlineModelConfig") + GetFieldID("rule1",...)
# Class/member renaming breaks the JNI handshake -> UnsatisfiedLinkError /
# NoSuchFieldError -> voice recognition silently disabled in release builds.
# Keep ALL sherpa-onnx classes, members, and native signatures intact.
-keep,includedescriptorclasses class com.k2fsa.sherpa.onnx.** { *; }

# Room: keep annotations and generated metadata, but allow class/interface names to be obfuscated.
-keep,allowobfuscation class com.yunian.ai.database.AppDatabase { *; }
-keep,allowobfuscation @androidx.room.Entity class com.yunian.ai.database.model.** { *; }
-keep,allowobfuscation interface com.yunian.ai.database.dao.** { *; }
-keep class androidx.room.** { *; }
-keep @androidx.room.Dao interface *
-keepclassmembers,allowobfuscation class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**

# Retrofit interfaces and HTTP annotations.
-keep,allowobfuscation interface com.yunian.ai.network.OpenAiApi { *; }
-keep,allowobfuscation interface com.yunian.ai.network.AnthropicApi { *; }
-keep,allowobfuscation interface com.yunian.ai.network.GeminiApi { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# Kotlin serialization: DTO obfuscation is safe because:
# - Custom serializers use string-based key lookups (no reflection on field names)
# - Auto-generated serializers respect @SerialName annotations for wire names
# - R8 preserves annotations via -keepattributes
# Class names, property names, and method names may all be obfuscated freely.

# Compose runtime needs annotations, not app composable names.
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Third-party SDKs that use reflection/native loading.
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn coil.**
-dontwarn androidx.work.**
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.security.crypto.**

# Crypto: keep JCA classes intact for AES-256-GCM encrypted API calls
-keep class javax.crypto.** { *; }
-keep class javax.crypto.spec.** { *; }
-dontwarn javax.crypto.**

# ViewModel constructors are created reflectively by AndroidX factories; allow
# class/method obfuscation while preserving constructors.
-keepclassmembers,allowobfuscation class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers,allowobfuscation class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}

# WeChat module — keep initialization path intact; R8 aggressive optimizations
# (mergeinterfacesaggressively/overloadaggressively) corrupt static initializers
# in these classes, causing SIGABRT on startup (YuNianApplication.initWeChat).
-keep class com.yunian.ai.feature.wechat.** { *; }
-keep class com.yunian.ai.feature.wechat.data.** { *; }
-keep class com.yunian.ai.feature.wechat.service.** { *; }

# DEX padding — must survive R8 to push method count past 64K, forcing multi-dex
-keep class com.yunian.ai.internal.DexPadding { *; }

# Android framework conventions.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.yunian.ai.R$* { *; }

# Enum converters need value lookup; names are persisted in Room values.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ================================================================
# Vendor Push SDKs (OPPO / vivo / Xiaomi / Huawei)
# ================================================================
# OPPO / vivo / Xiaomi SDKs are bundled as local aars and accessed via reflection.
# Keep their class names and members so Class.forName / getMethod work in release builds.
-keep class com.heytap.msp.push.** { *; }
-keep class com.coloros.mcs.** { *; }
-keep class com.vivo.push.** { *; }
-keep class com.xiaomi.mipush.sdk.** { *; }
-keep class com.xiaomi.push.** { *; }
-keep class com.huawei.hms.push.** { *; }
-dontwarn com.heytap.msp.push.**
-dontwarn com.coloros.mcs.**
-dontwarn com.vivo.push.**
-dontwarn com.xiaomi.mipush.sdk.**
-dontwarn com.xiaomi.push.**
# ═══════════════════════════════════════════════════════════════
# Ultimate Shell — protect shell DEX + assets from R8/strip
# ═══════════════════════════════════════════════════════════════

# Keep shell bootstrapping classes (in shell DEX, referenced by manifest)
-keep class com.yunian.ai.security.StaticApkShell { *; }
-keep class com.yunian.ai.security.SActivity { *; }
-keep class com.yunian.ai.security.MethodRecoveryEngine { *; }
-keep class com.yunian.ai.security.G0 { *; }

# Keep MainActivity (manifest LAUNCHER → must be resolvable by ClassLoader)
-keep class com.yunian.ai.MainActivity { *; }
-keep class com.yunian.ai.YuNianApplication { *; }

# Keep all native method declarations
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep AndroidX runtime (for WorkManager initialization in shell)
-keep class androidx.work.WorkManager { *; }
-keep class androidx.work.Configuration { *; }
-keep class androidx.work.Configuration$Builder { *; }
-keep class androidx.work.impl.WorkManagerInitializer { *; }

# Keep InMemoryDexClassLoader (used by shell at runtime)
-keep class dalvik.system.InMemoryDexClassLoader { *; }
-keep class dalvik.system.BaseDexClassLoader { *; }
-keep class dalvik.system.DexPathList { *; }
-keep class dalvik.system.DexPathList$Element { *; }

# ═══════════════════════════════════════════════════════════════
# WorkManager — preserve reflection-based initialization
# ═══════════════════════════════════════════════════════════════
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepattributes *Annotation*
-keep class androidx.work.impl.** { *; }
-keep class androidx.work.WorkManager { *; }
-keep class androidx.work.Configuration { *; }
-keep class androidx.work.Configuration$Builder { *; }
-keep class androidx.work.impl.WorkManagerInitializer { *; }

# ═══════════════════════════════════════════════════════════════
# Agent Native 桥接（UniFFI + JNA）—— release 专有故障的根因所在
#
# UniFFI 生成的绑定完全依赖 JNA：native 函数按「接口方法名」解析符号，
# RustBuffer.ByValue 等结构体的字段布局靠反射计算，回调（ToolHost /
# StreamSink / SkillStore / MemoryStore / …）经 JNA CallbackProxy 反射调用。
# 一旦 R8 改名或删除这些类/方法/字段，release 包会在运行期抛
# UnsatisfiedLinkError / NoSuchMethodError，并因 SecureLog 在非 debug 静默
# 而表现为「聊天界面 AI 不回复」——debug 包不复现。
#
# 事故证据（2026-09-28 release mapping.txt）：
#   com.sun.jna.CallbackProxy            -> ug0
#   com.sun.jna.CallbackReference        -> yg0
#   com.sun.jna.Library                  -> cd4
#   com.yunian.ai.agent.host.AgentToolHost -> d8
#   com.yunian.ai.agent.uniffi.AgentTurnRequest -> f8
#   com.yunian.ai.agent.uniffi.FfiConverterRustBuffer -> R8$$REMOVED$$CLASS$$
# ═══════════════════════════════════════════════════════════════
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * extends com.sun.jna.PointerType { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
    <init>();
}
-keepclassmembers class * implements com.sun.jna.Callback {
    <methods>;
}
-keep,allowoptimization class * implements com.sun.jna.Callback {
    <methods>;
}

# UniFFI 生成的 Kotlin 绑定（包名见 agent-native/uniffi.toml）
-keep class com.yunian.ai.agent.uniffi.** { *; }
-keepclassmembers class com.yunian.ai.agent.uniffi.** { *; }
-keep interface com.yunian.ai.agent.uniffi.** { *; }
-keep enum com.yunian.ai.agent.uniffi.** { *; }
-keep,includedescriptorclasses class com.yunian.ai.agent.uniffi.** { *; }

# 回调实现类（Kotlin 实现，经 JNA 被 Rust 反调）
-keep class com.yunian.ai.agent.AgentFacade { *; }
-keep class com.yunian.ai.agent.host.** { *; }
-keep class com.yunian.ai.agent.skill.** { *; }
-keepclassmembers class * implements com.yunian.ai.agent.uniffi.ToolHost { *; }
-keepclassmembers class * implements com.yunian.ai.agent.uniffi.StreamSink { *; }
-keepclassmembers class * implements com.yunian.ai.agent.uniffi.SkillStore { *; }
-keepclassmembers class * implements com.yunian.ai.agent.uniffi.MemoryStore { *; }
-keepclassmembers class * implements com.yunian.ai.agent.uniffi.RequestSignatureProvider { *; }
-keepclassmembers class * implements com.yunian.ai.agent.uniffi.StickerPreferenceStore { *; }
-keepclassmembers class * implements com.yunian.ai.agent.uniffi.TurnStateController { *; }

# JNI 声明方法名必须与 .so 符号一致
-keepclasseswithmembernames class * {
    native <methods>;
}

-dontwarn com.huawei.hms.**
-dontwarn com.huawei.android.os.**
-dontwarn com.huawei.hianalytics.**
-dontwarn com.huawei.libcore.io.**
-dontwarn org.apache.commons.codec.**
