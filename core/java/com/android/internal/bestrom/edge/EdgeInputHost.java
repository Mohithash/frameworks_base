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

import android.content.Context;
import android.view.InputEvent;

/**
 * The system side of the Edge bridge. Implemented by InputManagerService and
 * handed to the Edge app once, at the end of input manager start-up.
 *
 * @hide
 */
public interface EdgeInputHost {

    /**
     * The system context, for resources, broadcasts and system services.
     *
     * @hide
     */
    Context getSystemContext();

    /**
     * Injects an event Edge swallowed or synthesised. The event is marked
     * POLICY_FLAG_FILTERED and injected asynchronously, so it is not offered to
     * the input filter again.
     *
     * @hide
     */
    void sendInputEvent(InputEvent event, int policyFlags);

    /**
     * Turns the native input filter on or off for Edge. It stays on as long as
     * either Edge or a real input filter needs it.
     *
     * @hide
     */
    void setInputHookEnabled(boolean enabled);
}
