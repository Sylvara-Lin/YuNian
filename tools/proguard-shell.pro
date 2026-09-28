# ProGuard rules for shell DEX extraction
# Goal: tree-shake 9.5MB DEX → ~500KB shell-only DEX
# Strategy: KEEP only shell bootstrapping classes + their transitive Kotlin deps

# ═══════════════════════════════════════════
# 1. Shell entry points (Application + Activities)
# ═══════════════════════════════════════════
-keep public class com.yunian.ai.security.StaticApkShell {
    public protected *;
    native <methods>;
}
-keep public class com.yunian.ai.security.StaticApkShell$*
-keep public class com.yunian.ai.security.NativeBridge {
    public protected *;
    native <methods>;
}
-keep public class com.yunian.ai.security.G0 {
    public protected *;
}
-keep public class com.yunian.ai.security.MethodRecoveryEngine {
    public protected *;
    native <methods>;
}
-keep public class com.yunian.ai.security.SActivity { *; }
-keep public class com.yunian.ai.security.SService { *; }
-keep public class com.yunian.ai.security.SReceiver { *; }

# Manifest components
-keep public class com.yunian.ai.MainActivity { *; }
-keep public class com.yunian.ai.feature.notification.BootReceiver { *; }
-keep public class com.yunian.ai.feature.notification.CompanionKeepAliveService { *; }
-keep public class com.yunian.ai.feature.qqbot.service.QQBotForegroundService { *; }
-keep public class com.yunian.ai.push.receiver.VivoPushReceiver { *; }
-keep public class com.yunian.ai.push.service.HuaweiPushService { *; }
-keep public class com.yunian.ai.push.service.OppoPushService { *; }
-keep public class com.yunian.ai.push.service.OppoPushServiceLegacy { *; }

# ═══════════════════════════════════════════
# 2. Kotlin runtime — CRITICAL for shell to work
# ═══════════════════════════════════════════
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class org.jetbrains.annotations.** { *; }
-keepclassmembers class kotlin.Metadata { *; }
-keep class kotlin.coroutines.** { *; }
-keep class kotlin.jvm.** { *; }
-keep class kotlin.collections.** { *; }
-keep class kotlin.ranges.** { *; }
-keep class kotlin.sequences.** { *; }
-keep class kotlin.text.** { *; }
-keep class kotlin.io.** { *; }

# ═══════════════════════════════════════════
# 3. Native methods (JNI bridge) — never strip
# ═══════════════════════════════════════════
-keepclasseswithmembernames class * {
    native <methods>;
}

# ═══════════════════════════════════════════
# 4. Annotations and metadata
# ═══════════════════════════════════════════
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions, RuntimeVisible*Annotations
-keepattributes SourceFile, LineNumberTable

# ═══════════════════════════════════════════
# 5. AndroidX dependencies
# ═══════════════════════════════════════════
-keep class androidx.core.app.CoreComponentFactory { *; }
-keep class androidx.core.content.FileProvider { *; }
-keep class androidx.profileinstaller.ProfileInstallReceiver { *; }
-keep class androidx.room.MultiInstanceInvalidationService { *; }
-keep class androidx.startup.InitializationProvider { *; }
-keep class androidx.work.** { *; }

# ═══════════════════════════════════════════
# 6. HMS / Google Play deps
# ═══════════════════════════════════════════
-keep class com.huawei.** { *; }
-keep class com.google.android.play.** { *; }

# ═══════════════════════════════════════════
# 7. Dont warn (suppress warnings for missing classes)
# ═══════════════════════════════════════════
-dontwarn **
-dontnote **
-allowaccessmodification
-repackageclasses ''
