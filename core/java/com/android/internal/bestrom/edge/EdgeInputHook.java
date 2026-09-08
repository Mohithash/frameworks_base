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

import android.content.ClipData;
import android.os.IBinder;
import android.view.InputEvent;
import android.view.KeyEvent;

/**
 * Implemented by the Edge app and called from system services.
 *
 * Every method runs on a system_server thread that is on the input path, so it
 * must be cheap and must never throw. Callers guard with try/catch anyway.
 *
 * @hide
 */
public interface EdgeInputHook {

    /**
     * Input filter stage. Sees every key and motion event while the hook is
     * enabled, except the ones Edge injected itself (those carry
     * POLICY_FLAG_FILTERED and the native dispatcher skips the filter for them).
     *
     * @return true to consume the event, false to let dispatch continue
     * @hide
     */
    boolean onInputEvent(InputEvent event, int policyFlags);

    /**
     * Key interception stage, from InputManagerService.interceptKeyBeforeDispatching.
     *
     * @return -1 to consume the key, 0 to let the system handle it
     * @hide
     */
    long onInterceptKeyBeforeDispatching(IBinder focus, KeyEvent event, int policyFlags);

    /**
     * A new primary clip was recorded by ClipboardService.
     *
     * @hide
     */
    void onPrimaryClipChanged(ClipData clip, String sourcePackage, int userId);

    /**
     * When true, AccessibilityManagerService reports accessibility as enabled to
     * its clients even with no accessibility service running, so apps keep
     * populating AccessibilityNodeInfo trees for the universal copy feature.
     *
     * @hide
     */
    boolean wantsAccessibilityEnabled();
}
