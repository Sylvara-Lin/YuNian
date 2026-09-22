package com.yunian.ai.security;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.InMemoryDexClassLoader;

public class StaticApkShell extends Application {
    private static final String TAG = "StaticApkShell";
    private static final String REAL_APP = "com.yunian.ai.YuNianApplication";
    private static final String METRICS_PREFS = "release_performance_metrics";

    private Application realApplication;
    private long shellStarted;
    private long antiHookDone;
    private long certificateDone;
    private long vmpPayloadDone;
    private long dexLoadDone;
    private long recoveryDone;
    private long memoryGuardDone;
    private long realAppCreated;
    private long realAppAttached;

    static {
        System.loadLibrary("lianyu_shell");
    }

    @Override
    protected void attachBaseContext(Context base) {
        shellStarted = SystemClock.elapsedRealtimeNanos();
        super.attachBaseContext(base);
        stageLog("attach.begin");
        try {
            nativeAntiHookInit();
        } catch (Throwable t) {
            Log.w(TAG, "antiHook init failed (HarmonyOS/EMUI compatibility): " + t.getClass().getSimpleName());
        }
        antiHookDone = SystemClock.elapsedRealtimeNanos();
        stageLog("attach.antiHook", antiHookDone - shellStarted);
        try {
            initApkCertificate(base);
        } catch (Throwable t) {
            Log.w(TAG, "certificate init failed: " + t.getClass().getSimpleName());
        }
        certificateDone = SystemClock.elapsedRealtimeNanos();
        stageLog("attach.certificate", certificateDone - antiHookDone);

        vmpPayloadDone = certificateDone;
        stageLog("attach.vmpPayload.skipped");
        try {
            loadEncryptedDex(base);
        } catch (Throwable t) {
            Log.e(TAG, "DEX loading failed - app cannot start: " + t.getClass().getSimpleName(), t);
            persistAttachMetrics(base);
            return;
        }
        dexLoadDone = SystemClock.elapsedRealtimeNanos();
        stageLog("attach.dexLoad", dexLoadDone - vmpPayloadDone);
        try {
            MethodRecoveryEngine.install(base.getClassLoader());
        } catch (Throwable t) {
            Log.w(TAG, "recovery engine install failed: " + t.getClass().getSimpleName());
        }
        recoveryDone = SystemClock.elapsedRealtimeNanos();
        stageLog("attach.recovery", recoveryDone - dexLoadDone);
        try {
            nativeEnableMemoryGuard();
        } catch (Throwable t) {
            Log.w(TAG, "memory guard init failed: " + t.getClass().getSimpleName());
        }
        memoryGuardDone = SystemClock.elapsedRealtimeNanos();
        stageLog("attach.memoryGuard", memoryGuardDone - recoveryDone);
        try {
            realApplication = createRealApplication(base);
        } catch (Throwable t) {
            Log.e(TAG, "real application creation failed: " + t.getClass().getSimpleName(), t);
            persistAttachMetrics(base);
            return;
        }
        realAppCreated = SystemClock.elapsedRealtimeNanos();
        stageLog("attach.realAppCreate", realAppCreated - memoryGuardDone);
        if (realApplication != null) {
            try {
                attachRealApplication(base, realApplication);
            } catch (Throwable t) {
                Log.e(TAG, "real application attach failed: " + t.getClass().getSimpleName(), t);
                persistAttachMetrics(base);
                return;
            }
        }
        realAppAttached = SystemClock.elapsedRealtimeNanos();
        stageLog("attach.realAppAttach", realAppAttached - realAppCreated);
        stageLog("attach.total", realAppAttached - shellStarted);
        persistAttachMetrics(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        long businessStarted = SystemClock.elapsedRealtimeNanos();
        stageLog("onCreate.begin", businessStarted - shellStarted);
        try {
            runSecurityPreflight();
        } catch (Throwable t) {
            Log.w(TAG, "security preflight failed: " + t.getClass().getSimpleName());
        }
        long preflightDone = SystemClock.elapsedRealtimeNanos();
        stageLog("onCreate.preflight", preflightDone - businessStarted);
        try {
            runSecurityRuntimeInit();
        } catch (Throwable t) {
            Log.w(TAG, "security runtime init failed: " + t.getClass().getSimpleName());
        }
        long runtimeDone = SystemClock.elapsedRealtimeNanos();
        stageLog("onCreate.runtime", runtimeDone - preflightDone);
        if (realApplication != null) {
            try {
                long wmStarted = SystemClock.elapsedRealtimeNanos();
                initializeWorkManager();
                stageLog("onCreate.workManager", SystemClock.elapsedRealtimeNanos() - wmStarted);
            } catch (Throwable t) {
                Log.w(TAG, "WorkManager init failed: " + t.getClass().getSimpleName());
            }
            long appStarted = SystemClock.elapsedRealtimeNanos();
            try {
                realApplication.onCreate();
            } catch (Throwable t) {
                Log.e(TAG, "real application onCreate failed: " + t.getClass().getSimpleName(), t);
            }
            stageLog("onCreate.realApp", SystemClock.elapsedRealtimeNanos() - appStarted);
        }
        long businessDone = SystemClock.elapsedRealtimeNanos();
        stageLog("onCreate.total", businessDone - businessStarted);
        stageLog("startup.total", businessDone - shellStarted);
        getSharedPreferences(METRICS_PREFS, MODE_PRIVATE).edit()
                .putLong("java_shell_business_on_create", businessDone - businessStarted)
                .putLong("java_shell_to_business_ready", businessDone - shellStarted)
                .putLong("java_shell_business_run_id", shellStarted)
                .commit();
    }

    private void stageLog(String stage) {
        Log.i(TAG, "stage " + stage);
    }

    private void stageLog(String stage, long nanos) {
        Log.i(TAG, "stage " + stage + " ms=" + (nanos / 1_000_000L));
    }

    private void persistBusinessBlocked(long businessStarted) {
        long now = SystemClock.elapsedRealtimeNanos();
        getSharedPreferences(METRICS_PREFS, MODE_PRIVATE).edit()
                .putLong("java_shell_business_on_create", now - businessStarted)
                .putLong("java_shell_to_business_ready", -1L)
                .putLong("java_shell_business_run_id", shellStarted)
                .putBoolean("java_shell_business_blocked", true)
                .commit();
    }

    private void runSecurityPreflight() {
        try {
            invokeKotlinObjectMethod("com.yunian.ai.security.G0", "b", this);
        } catch (Throwable error) {
            Log.w(TAG, "security preflight unavailable: " + error.getClass().getSimpleName()
                    + (error.getMessage() != null ? (": " + error.getMessage()) : ""));
        }
    }

    private void runSecurityRuntimeInit() {
        try {
            invokeKotlinObjectMethod("com.yunian.ai.security.G0", "a", this);
        } catch (Throwable error) {
            Log.w(TAG, "security runtime init unavailable: " + error.getClass().getSimpleName()
                    + (error.getMessage() != null ? (": " + error.getMessage()) : ""));
        }
    }

    private boolean canStartLocalBusiness() {
        try {
            Class<?> state = Class.forName("com.yunian.ai.security.SecurityState", true, getClassLoader());
            Object result = invokeKotlinObjectMethod(state, "canStartLocalBusiness");
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable error) {
            Log.w(TAG, "SecurityState unavailable, allowing local business: " + error.getClass().getSimpleName());
            return true;
        }
    }

    private Object invokeKotlinObjectMethod(String className, String methodName, Object... args) throws Exception {
        Class<?> clazz = Class.forName(className, true, getClassLoader());
        return invokeKotlinObjectMethod(clazz, methodName, args);
    }

    private Object invokeKotlinObjectMethod(Class<?> clazz, String methodName, Object... args) throws Exception {
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {

            if (args[i] instanceof Context) {
                paramTypes[i] = Context.class;
            } else {
                paramTypes[i] = args[i].getClass();
            }
        }
        Method method = clazz.getMethod(methodName, paramTypes);
        Object target = null;
        try {
            Field instance = clazz.getField("INSTANCE");
            target = instance.get(null);
        } catch (NoSuchFieldException ignored) {

        }
        return method.invoke(target, args);
    }

    private void initializeWorkManager() {
        try {
            ClassLoader loader = getClassLoader();
            Class<?> configurationClass = Class.forName("androidx.work.Configuration", true, loader);
            Class<?> builderClass = Class.forName("androidx.work.Configuration$Builder", true, loader);
            Object builder = builderClass.getDeclaredConstructor().newInstance();
            Object configuration = builderClass.getMethod("build").invoke(builder);
            Class<?> workManagerClass = Class.forName("androidx.work.WorkManager", true, loader);
            workManagerClass.getMethod("initialize", Context.class, configurationClass)
                    .invoke(null, this, configuration);
        } catch (Throwable error) {
            throw new RuntimeException("initialize WorkManager failed", error);
        }
    }

    private void persistAttachMetrics(Context context) {
        SharedPreferences.Editor editor = context.getSharedPreferences(METRICS_PREFS, MODE_PRIVATE).edit();
        editor.putLong("java_shell_anti_hook", antiHookDone - shellStarted);
        editor.putLong("java_shell_certificate", certificateDone - antiHookDone);
        editor.putLong("java_shell_vmp_payload", vmpPayloadDone - certificateDone);
        editor.putLong("java_shell_dex_load", dexLoadDone - vmpPayloadDone);
        editor.putLong("java_shell_recovery", recoveryDone - dexLoadDone);
        editor.putLong("java_shell_memory_guard", memoryGuardDone - recoveryDone);
        editor.putLong("java_shell_real_app_create", realAppCreated - memoryGuardDone);
        editor.putLong("java_shell_real_app_attach", realAppAttached - realAppCreated);
        editor.putLong("java_shell_attach_total", realAppAttached - shellStarted);
        editor.putLong("java_shell_attach_run_id", shellStarted);
        editor.commit();
    }

    private void initApkCertificate(Context context) {
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= 28) {
                info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                Signature[] signatures = info.signingInfo.getApkContentsSigners();
                if (signatures != null && signatures.length > 0) {
                    byte[] certDer = signatures[0].toByteArray();
                    byte[] certSha256 = MessageDigest.getInstance("SHA-256").digest(certDer);
                    nativeSetApkCert(certSha256);
                }
            } else {
                info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
                if (info.signatures != null && info.signatures.length > 0) {
                    byte[] certDer = info.signatures[0].toByteArray();
                    byte[] certSha256 = MessageDigest.getInstance("SHA-256").digest(certDer);
                    nativeSetApkCert(certSha256);
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "set apk cert failed", error);
        }
    }

    private void initVmpPayload(Context context) {
        try {
            byte[] blob = readAsset(context, "yunian_shell/code_items.bin");
            nativeShellInitWithBlob(blob);
        } catch (Throwable ignored) {
        }
    }

    private void loadEncryptedDex(Context context) {
        try {
            byte[] dexKey = nativeDeriveDexKey();
            byte[] meta = readAsset(context, "shell/app_meta.bin");
            String realAppName = decryptString(meta, dexKey);
            List<ByteBuffer> buffers = new ArrayList<>();
            int index = 0;
            while (true) {
                String name = index == 0 ? "shell/classes.dat" : "shell/classes" + (index + 1) + ".dat";
                try {
                    byte[] encrypted = readAsset(context, name);
                    byte[] decrypted = nativeDecryptDex(encrypted, dexKey);
                    buffers.add(ByteBuffer.wrap(decrypted));
                    index++;
                } catch (Throwable missing) {
                    break;
                }
            }
            if (buffers.isEmpty()) {
                throw new IllegalStateException("no encrypted dex assets");
            }
            InMemoryDexClassLoader loader = new InMemoryDexClassLoader(buffers.toArray(new ByteBuffer[0]), context.getClassLoader());
            mergeDexElements(context.getClassLoader(), loader);
            if (realAppName != null && realAppName.length() > 0 && !REAL_APP.equals(realAppName)) {
                Log.i(TAG, "real app from meta: " + realAppName);
            }
        } catch (Throwable error) {
            throw new RuntimeException("load encrypted dex failed", error);
        }
    }

    private Application createRealApplication(Context context) {
        try {
            Class<?> appClass = Class.forName(REAL_APP, true, context.getClassLoader());
            return (Application) appClass.getDeclaredConstructor().newInstance();
        } catch (Throwable error) {
            throw new RuntimeException("create real application failed", error);
        }
    }

    private void attachRealApplication(Context context, Application application) {
        try {

            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            attach.invoke(application, context);
        } catch (Throwable error) {
            throw new RuntimeException("attach real application failed", error);
        }
    }

    private String decryptString(byte[] encrypted, byte[] dexKey) {
        try {
            byte[] data = nativeDecryptDex(encrypted, dexKey);
            return new String(data, "UTF-8");
        } catch (Throwable error) {
            return REAL_APP;
        }
    }

    private static byte[] readAsset(Context context, String name) throws Exception {
        InputStream input = context.getAssets().open(name);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static void mergeDexElements(ClassLoader target, ClassLoader source) throws Exception {
        Object targetPathList = getField(target, "pathList");
        Object sourcePathList = getField(source, "pathList");
        Object[] targetElements = (Object[]) getField(targetPathList, "dexElements");
        Object[] sourceElements = (Object[]) getField(sourcePathList, "dexElements");
        Object[] merged = (Object[]) java.lang.reflect.Array.newInstance(targetElements.getClass().getComponentType(), targetElements.length + sourceElements.length);
        System.arraycopy(sourceElements, 0, merged, 0, sourceElements.length);
        System.arraycopy(targetElements, 0, merged, sourceElements.length, targetElements.length);
        setField(targetPathList, "dexElements", merged);
    }

    private static Object getField(Object instance, String name) throws Exception {
        Field field = findField(instance.getClass(), name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void setField(Object instance, String name, Object value) throws Exception {
        Field field = findField(instance.getClass(), name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private native int nativeShellInitWithBlob(byte[] blob);
    private native void nativeEnableMemoryGuard();
    private native void nativeAntiHookInit();
    private native void nativeSetApkCert(byte[] certSha256);
    private native byte[] nativeDeriveDexKey();
    private native byte[] nativeDecryptDex(byte[] encrypted, byte[] wbKey);
}
