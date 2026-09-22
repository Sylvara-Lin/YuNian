# Keep shell entry classes in main DEX to prevent ClassNotFoundException at startup
-keep class com.yunian.ai.security.StaticApkShell { *; }
-keep class com.yunian.ai.security.SActivity { *; }
-keep class com.yunian.ai.security.MethodRecoveryEngine { *; }
-keep class com.yunian.ai.MainActivity { *; }
-keep class com.yunian.ai.YuNianApplication { *; }
