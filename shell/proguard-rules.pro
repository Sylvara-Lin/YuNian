# Shell DEX ProGuard rules — keep only essential shell classes
# 目标: ≤ 5 个类, ≤ 12KB

# Keep shell entry classes
-keep class com.stub.StubApp { *; }
-keep class com.stub.Bridge { *; }

# Keep Application lifecycle methods
-keepclassmembers class com.stub.StubApp {
    void attachBaseContext(android.content.Context);
    void onCreate();
    native <methods>;
}

-keepclassmembers class com.stub.Bridge {
    native <methods>;
}

# Strip everything else
-dontwarn **
-keepattributes SourceFile,LineNumberTable
