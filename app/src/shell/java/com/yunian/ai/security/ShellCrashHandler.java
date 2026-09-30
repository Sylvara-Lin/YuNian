package com.yunian.ai.security;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Shell-stage crash safety net. **Pure Java, zero business dependencies.**
 *
 * Why this exists: after thin-shell packaging, the real Application is StaticApkShell in the
 * root DEX; the business DEX (which contains CrashReporter) is only loaded afterwards via
 * InMemoryDexClassLoader. If a crash happens during the shell stage (DEX decrypt/load, native
 * init, certificate/VMP checks), the business DEX is not loaded yet and the business-side
 * handler never takes effect -- and that is exactly the stage most likely to crash on Huawei
 * devices. Therefore this class must be fully self-contained and use only java.* / android.*.
 *
 * Contract (shares a filesystem convention with com.yunian.ai.common.crash.CrashLogStore):
 *  - writes filesDir/crash/crash_shell.txt;
 *  - if the business layer already took over (.business_alive in filesDir/crash holds the
 *    current PID), this handler SKIPS writing, so the minimal shell report cannot overwrite the
 *    richer business report;
 *  - on install it deletes a stale .business_alive left by a previous process (PID reuse);
 *  - CHAINS to the previous handler -- never swallows the exception.
 *
 * NOTE: this file is compiled by a bare `javac` call in tools/build.py without an explicit
 * -encoding flag, so it MUST stay ASCII-only. Do not add non-ASCII characters here.
 */
final class ShellCrashHandler {

    private static final String DIR = "crash";
    private static final String SHELL_FILE = "crash_shell.txt";
    private static final String ALIVE_FILE = ".business_alive";
    private static final int MAX_CHARS = 128 * 1024;

    private static volatile boolean installed = false;

    private ShellCrashHandler() {
    }

    /**
     * Install the shell-stage handler. Safe to call from the earliest point of
     * Application.attachBaseContext.
     *
     * @param context any usable Context (the base passed to attachBaseContext works).
     */
    static void install(final Context context) {
        if (installed) {
            return;
        }
        installed = true;

        final Context app;
        try {
            app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        } catch (Throwable t) {
            // Fall back to the raw context.
            installHandler(context);
            return;
        }

        // A new process started: any alive-marker from a previous process is stale, so clear it
        // to avoid a PID-reuse false positive.
        try {
            File alive = new File(new File(app.getFilesDir(), DIR), ALIVE_FILE);
            if (alive.exists()) {
                alive.delete();
            }
        } catch (Throwable ignored) {
        }

        installHandler(app);
    }

    private static void installHandler(final Context app) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    if (!isBusinessAliveInThisProcess(app)) {
                        writeShellCrash(app, buildReport(thread, throwable));
                    }
                } catch (Throwable ignored) {
                    // A failure to persist must never affect normal process termination.
                } finally {
                    if (previous != null) {
                        try {
                            previous.uncaughtException(thread, throwable);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        });
    }

    private static boolean isBusinessAliveInThisProcess(Context context) {
        try {
            File alive = new File(new File(context.getFilesDir(), DIR), ALIVE_FILE);
            if (!alive.exists()) {
                return false;
            }
            String pid = readText(alive).trim();
            return pid.equals(Integer.toString(Process.myPid()));
        } catch (Throwable t) {
            return false;
        }
    }

    private static void writeShellCrash(Context context, String text) {
        try {
            File dir = new File(context.getFilesDir(), DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String payload = text;
            if (payload.length() > MAX_CHARS) {
                payload = payload.substring(0, MAX_CHARS) + "\n... (truncated)\n";
            }
            File target = new File(dir, SHELL_FILE);
            File tmp = new File(dir, SHELL_FILE + ".tmp");
            writeText(tmp, payload);
            if (target.exists()) {
                target.delete();
            }
            tmp.renameTo(target);
        } catch (Throwable ignored) {
            // Nothing we can do; stay silent.
        }
    }

    private static String buildReport(Thread thread, Throwable throwable) {
        long now = System.currentTimeMillis();
        StringWriter sw = new StringWriter(4096);
        PrintWriter pw = new PrintWriter(sw);
        try {
            throwable.printStackTrace(pw);
            pw.flush();
        } catch (Throwable ignored) {
        }
        return "=== YuNian Crash Report ===\n"
                + "source: shell\n"
                + "recorded_at: " + formatTime(now) + " (epoch_ms=" + now + ")\n"
                + "thread: " + thread.getName() + " (id=" + thread.getId() + ")\n"
                + "process: pid=" + safePid() + "\n"
                + "device: " + deviceInfo() + "\n"
                + "exception: " + throwable.getClass().getName() + "\n"
                + "message: " + (throwable.getMessage() == null ? "(null)" : throwable.getMessage()) + "\n"
                + "--- stack ---\n"
                + sw + "\n"
                + "(shell stage: business layer not loaded; breadcrumbs unavailable)\n"
                + "=== end ===\n";
    }

    private static String deviceInfo() {
        try {
            return "manufacturer=" + Build.MANUFACTURER
                    + ", brand=" + Build.BRAND
                    + ", model=" + Build.MODEL
                    + ", sdk=" + Build.VERSION.SDK_INT
                    + ", release=" + Build.VERSION.RELEASE;
        } catch (Throwable t) {
            return "(device info unavailable)";
        }
    }

    @SuppressWarnings("unused")
    private static String appInfo(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return "versionName=" + info.versionName + ", versionCode=" + info.versionCode;
        } catch (Throwable t) {
            return "(app info unavailable)";
        }
    }

    private static int safePid() {
        try {
            return Process.myPid();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String formatTime(long epochMs) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(epochMs));
        } catch (Throwable t) {
            return Long.toString(epochMs);
        }
    }

    private static String readText(File file) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void writeText(File file, String text) throws Exception {
        java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(file), "UTF-8");
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }
}
