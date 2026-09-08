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
 * Entry point of the Edge app inside system_server. The class
 * com.bestrom.edge.core.EdgeEntry in /system_ext/priv-app/Edge/Edge.apk
 * implements this and has a public no-argument constructor.
 *
 * @hide
 */
public interface EdgeSystemEntry {

    /**
     * Called once on the system_server main thread. The implementation may
     * install its hook later.
     *
     * @hide
     */
    void start(EdgeInputHost host);
}
