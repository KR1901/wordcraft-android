package com.wordcraft.probe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Zero-dependency probe. No androidx, no Compose, no Kotlin, no native libraries, no Application
 * class, no providers. If this screen appears, the device runs APKs produced by this build stack
 * and the problem lives in WordCraft's own dependency graph. If it does not appear, the problem is
 * environmental (OS version, ABI, installer) and this report is what pins it down.
 */
public class ProbeActivity extends Activity {

    private String report = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        report = buildReport();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(24));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("WordCraft probe");
        title.setTextColor(Color.BLACK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView lead = new TextView(this);
        lead.setText("If you can read this, the device runs an app built from this repository with "
                + "no androidx, no Compose and no native code. Share the block below.");
        lead.setTextColor(Color.DKGRAY);
        lead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        lead.setPadding(0, dp(8), 0, dp(12));
        root.addView(lead);

        TextView body = new TextView(this);
        body.setText(report);
        body.setTextColor(Color.BLACK);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        root.addView(body);

        Button share = new Button(this);
        share.setText("Share this report");
        share.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "WordCraft probe report");
            send.putExtra(Intent.EXTRA_TEXT, report);
            startActivity(Intent.createChooser(send, "Share probe report"));
        });
        root.addView(share, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("WordCraft probe 0.0.1\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Build id: ").append(Build.DISPLAY).append('\n');
        sb.append("Supported ABIs: ").append(join(Build.SUPPORTED_ABIS)).append('\n');
        sb.append("64-bit ABIs: ").append(join(Build.SUPPORTED_64_BIT_ABIS)).append('\n');
        sb.append("32-bit ABIs: ").append(join(Build.SUPPORTED_32_BIT_ABIS)).append('\n');
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            sb.append("Process is 64-bit: ").append(Process.is64Bit()).append('\n');
        }
        sb.append("Memory class: ").append(getMemoryClassMb()).append(" MB\n");
        sb.append("Page size: ").append(pageSize()).append(" bytes\n");
        sb.append("Native libs in APK: none\n");
        sb.append("androidx / Compose in APK: none\n");
        return sb.toString();
    }

    private int getMemoryClassMb() {
        try {
            android.app.ActivityManager manager =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            return manager != null ? manager.getMemoryClass() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Memory page size via reflection: OsConstants' constant name differs between SDK levels and
     * a compile-time reference would break the build on the ones that spell it differently.
     */
    private String pageSize() {
        for (String constant : new String[] {"SC_PAGESIZE", "SC_PAGE_SIZE"}) {
            try {
                Class<?> os = Class.forName("android.system.Os");
                Class<?> constants = Class.forName("android.system.OsConstants");
                int id = constants.getField(constant).getInt(null);
                Object value = os.getMethod("sysconf", int.class).invoke(null, id);
                return String.valueOf(value);
            } catch (Throwable ignored) {
                // try the next spelling
            }
        }
        return "unknown";
    }

    private static String join(String[] values) {
        if (values == null || values.length == 0) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
