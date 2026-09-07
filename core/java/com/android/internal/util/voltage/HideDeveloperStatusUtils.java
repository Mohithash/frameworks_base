/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 *
 * Recovered 2026-08-19 from the r49 framework.jar decompile
 * (framework.jar ships unobfuscated, so the class name, method names, exact
 * signatures and every branch below are the originals; only javadoc, local
 * variable names and generics were restored). Styled after its sibling
 * HideAppListUtils in this package, which it was clearly derived from.
 *
 * NOTE: the six entries of settingsToHide appear in the dex as a mix of
 * literals and symbolic constants only because javac inlines static final
 * Strings and jadx cannot disambiguate "adb_enabled" /
 * "development_settings_enabled" between Settings.Global and the deprecated
 * Settings.Secure aliases. The Global names are used here; the emitted
 * strings are identical either way.
 */
package com.android.internal.util.voltage;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-app spoofing of "is this device in a developer/debuggable state?" signals.
 *
 * <p>A single "hide_developer_status" CSV list of package names
 * covers every key in {@link #settingsToHide} at once: an app on the list reads
 * the disabled/default value for all of them rather than the real one.
 */
public class HideDeveloperStatusUtils {

    private static volatile boolean sHideForProcess;

    private static final Map<String, String> PROP_SPOOFS;
    static {
        PROP_SPOOFS = new HashMap<>();
        PROP_SPOOFS.put("persist.sys.usb.config", "mtp");
        PROP_SPOOFS.put("sys.usb.config", "mtp");
        PROP_SPOOFS.put("init.svc.adbd", "stopped");
    }

    public static void setHideForProcess(boolean enabled) {
        sHideForProcess = enabled;
    }

    public static String getSpoofedProperty(String key) {
        if (!sHideForProcess) return null;
        return PROP_SPOOFS.get(key);
    }

    private static final Set<String> settingsToHide = new HashSet<>(Arrays.asList(
            Settings.Global.ADB_ENABLED,
            Settings.Global.ADB_WIFI_ENABLED,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            Settings.Global.PACKAGE_VERIFIER_SETTING_VISIBLE,
            Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB,
            Settings.Secure.ACCESSIBILITY_ENABLED));

    enum Action {
        ADD,
        REMOVE,
        SET
    }

    /**
     * @param packageName the app doing the read
     * @param setting the settings key being read
     * @return true if this app should be shown the spoofed value for this key
     */
    public static boolean shouldHideDevStatus(
            ContentResolver cr, String packageName, String setting) {
        if (cr == null || packageName == null || setting == null
                || !settingsToHide.contains(setting)) {
            return false;
        }

        Set<String> apps = getApps(cr);
        if (apps.isEmpty()) {
            return false;
        }

        return apps.contains(packageName);
    }

    private static Set<String> getApps(Context context) {
        if (context == null) {
            return new HashSet<>();
        }

        return getApps(context.getContentResolver());
    }

    private static Set<String> getApps(ContentResolver cr) {
        if (cr == null) {
            return new HashSet<>();
        }

        try {
            String apps = Settings.Secure.getString(cr, Settings.Secure.HIDE_DEVELOPER_STATUS);
            if (apps != null && !apps.isEmpty() && !apps.equals(",")) {
                return new HashSet<>(Arrays.asList(apps.split(",")));
            }
            return new HashSet<>();
        } catch (IllegalStateException e) {
            return new HashSet<>();
        }
    }

    private static void putAppsForUser(
            Context context, String packageName, int userId, Action action) {
        if (context == null || userId < 0) {
            return;
        }

        final Set<String> apps = getApps(context);
        switch (action) {
            case ADD:
                apps.add(packageName);
                break;
            case REMOVE:
                apps.remove(packageName);
                break;
            case SET:
                // Do not change
                break;
        }

        Settings.Secure.putStringForUser(
                context.getContentResolver(),
                Settings.Secure.HIDE_DEVELOPER_STATUS,
                String.join(",", apps),
                userId);
    }

    public void addApp(Context context, String packageName, int userId) {
        if (context == null || packageName == null || userId < 0) {
            return;
        }

        putAppsForUser(context, packageName, userId, Action.ADD);
    }

    public void removeApp(Context context, String packageName, int userId) {
        if (context == null || packageName == null || userId < 0) {
            return;
        }

        putAppsForUser(context, packageName, userId, Action.REMOVE);
    }

    public void setApps(Context context, int userId) {
        if (context == null || userId < 0) {
            return;
        }

        putAppsForUser(context, null, userId, Action.SET);
    }
}
