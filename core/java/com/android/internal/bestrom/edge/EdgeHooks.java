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

package com.android.internal.bestrom.edge;

/**
 * Holds the hook the Edge app installed. System services read it on every event,
 * so the field is volatile and the getter does nothing else.
 *
 * @hide
 */
public final class EdgeHooks {

    /**
     * Log tag shared by every Edge call site in the system services.
     *
     * @hide
     */
    public static final String TAG = "BestromEdge";

    private static volatile EdgeInputHook sHook;

    private EdgeHooks() {
    }

    /**
     * Installs the hook, or clears it when null.
     *
     * @hide
     */
    public static void set(EdgeInputHook hook) {
        sHook = hook;
    }

    /**
     * The installed hook, or null when Edge is not loaded.
     *
     * @hide
     */
    public static EdgeInputHook get() {
        return sHook;
    }
}
