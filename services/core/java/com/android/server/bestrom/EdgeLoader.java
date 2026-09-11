/*
 * Copyright (C) 2026 The BestROM Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.bestrom;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.internal.bestrom.edge.EdgeHooks;
import com.android.internal.bestrom.edge.EdgeInputHost;
import com.android.internal.bestrom.edge.EdgeSystemEntry;

import dalvik.system.PathClassLoader;

import java.io.File;

/**
 * Loads the Edge app into system_server.
 *
 * Edge used to be an Xposed module. Instead of injecting it, the input manager
 * calls this at the end of its start-up: the privileged APK is opened with a
 * class loader whose parent is the one that loaded the system services, and its
 * entry point is handed a host it can inject events through. The app then
 * installs its hook with EdgeHooks.set().
 *
 * A missing APK is normal (the product may not ship Edge) and anything that goes
 * wrong here is logged and swallowed. system_server does not fail to boot
 * because of Edge.
 *
 * Important: {@code pm disable-user com.bestrom.edge} does <b>not</b> remove the
 * APK from {@code /system_ext}. Without an enabled-state check here, Edge keeps
 * loading into system_server and can still arm the global input filter — which
 * is exactly why "I disabled Edge and glide still fails" is not a clean A/B.
 */
public final class EdgeLoader {

    private static final String TAG = EdgeHooks.TAG;

    private static final String APK_PATH = "/system_ext/priv-app/Edge/Edge.apk";
    private static final String ENTRY_CLASS = "com.bestrom.edge.core.EdgeEntry";
    private static final String PACKAGE_NAME = "com.bestrom.edge";
    /** Set to 1 to skip Edge for the whole boot. Default is off so a shipped
     *  Edge package loads; ContinuousTouchPolicy keeps L/R/bottom as passthrough
     *  and only arms the global filter when a TOP zone is assigned. */
    private static final String DISABLE_PROP = "persist.bestrom.edge.disable";

    private static boolean sStarted;

    private EdgeLoader() {
    }

    /**
     * Starts the Edge app, once per boot.
     */
    public static void start(EdgeInputHost host) {
        if (host == null) {
            return;
        }
        synchronized (EdgeLoader.class) {
            if (sStarted) {
                return;
            }
            sStarted = true;
        }
        try {
            // Default false: load Edge when the APK is present. Set the prop to
            // 1 and reboot to skip without removing the package.
            if (SystemProperties.getBoolean(DISABLE_PROP, false)) {
                skip(host, "disabled by " + DISABLE_PROP);
                return;
            }
            if (isPackageDisabled(host.getSystemContext())) {
                skip(host, "package " + PACKAGE_NAME + " is disabled");
                return;
            }
            final File apk = new File(APK_PATH);
            if (!apk.exists()) {
                Slog.i(TAG, "Edge is not installed, skipping");
                return;
            }
            final ClassLoader loader = new PathClassLoader(
                    apk.getAbsolutePath(), EdgeLoader.class.getClassLoader());
            final Class<?> clazz = loader.loadClass(ENTRY_CLASS);
            final EdgeSystemEntry entry =
                    (EdgeSystemEntry) clazz.getDeclaredConstructor().newInstance();
            entry.start(host);
            Slog.i(TAG, "Edge started");
        } catch (Throwable t) {
            Slog.e(TAG, "Failed to start Edge", t);
            try {
                host.setInputHookEnabled(false);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void skip(EdgeInputHost host, String reason) {
        Slog.i(TAG, "Edge skipped: " + reason);
        try {
            host.setInputHookEnabled(false);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to disarm input hook while skipping Edge", t);
        }
    }

    private static boolean isPackageDisabled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            final PackageManager pm = context.getPackageManager();
            final int state = pm.getApplicationEnabledSetting(PACKAGE_NAME);
            switch (state) {
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                    return true;
                default:
                    break;
            }
            // Default-enabled packages report DEFAULT; still verify the app
            // exists and is not hidden from the resolved ApplicationInfo.
            final ApplicationInfo info = pm.getApplicationInfo(PACKAGE_NAME, 0);
            return !info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            // No package record — treat as absent, not "disabled while APK
            // present". The File.exists() check below decides that.
            return false;
        } catch (Throwable t) {
            Slog.w(TAG, "Could not read Edge package enabled state", t);
            return false;
        }
    }
}
