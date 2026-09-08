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
 */
public final class EdgeLoader {

    private static final String TAG = EdgeHooks.TAG;

    private static final String APK_PATH = "/system_ext/priv-app/Edge/Edge.apk";
    private static final String ENTRY_CLASS = "com.bestrom.edge.core.EdgeEntry";

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
        }
    }
}
