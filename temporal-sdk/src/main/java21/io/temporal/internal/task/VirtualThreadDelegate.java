/*
 * Copyright (C) 2024 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.task;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;



/**
 * Internal delegate for virtual thread handling on JDK 21.
 * This is the actual version compiled against JDK 21.
 */
public final class VirtualThreadDelegate {

    private final Thread.Builder threadBuilder = Thread.ofVirtual();

    public VirtualThreadDelegate() {
    }

    public ThreadFactory virtualThreadFactory() {
        return threadBuilder.factory();
    }

    public ExecutorService newVirtualThreadExecutor(ThreadConfigurator configurator) {
        return Executors.newThreadPerTaskExecutor(
                r -> {
                    Thread t = threadBuilder.unstarted(r);
                    configurator.configure(t);
                    return t;
                });
    }
}

