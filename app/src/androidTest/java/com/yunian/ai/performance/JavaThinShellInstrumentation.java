package com.yunian.ai.performance;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

public final class JavaThinShellInstrumentation extends Instrumentation {
    private static final String[] METRICS = {
            "java_shell_anti_hook",
            "java_shell_certificate",
            "java_shell_vmp_payload",
            "java_shell_dex_load",
            "java_shell_recovery",
            "java_shell_memory_guard",
            "java_shell_real_app_create",
            "java_shell_real_app_attach",
            "java_shell_attach_total",
            "java_shell_business_on_create",
            "java_shell_to_business_ready"
    };

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Context targetContext = getTargetContext();
        SharedPreferences preferences = targetContext.getSharedPreferences(
            "release_performance_metrics",
                Context.MODE_PRIVATE
        );
        long deadline = System.currentTimeMillis() + 10_000L;
        while (preferences.getLong("java_shell_business_run_id", 0L)
                != preferences.getLong("java_shell_attach_run_id", -1L)
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Bundle results = new Bundle();
        boolean complete = preferences.getLong("java_shell_business_run_id", 0L)
                == preferences.getLong("java_shell_attach_run_id", -1L);
        for (String metric : METRICS) {
            long nanos = preferences.getLong(metric, 0L);
            complete &= nanos > 0L;
            results.putString("performance." + metric + "_ms", Double.toString(nanos / 1_000_000.0));
        }
        results.putString("performance.metrics_complete", Boolean.toString(complete));
        finish(complete ? Activity.RESULT_OK : Activity.RESULT_CANCELED, results);
    }
}