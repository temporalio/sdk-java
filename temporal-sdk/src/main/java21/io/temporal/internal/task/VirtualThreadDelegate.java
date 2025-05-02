
package io.temporal.internal.task;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Internal delegate for virtual thread handling on JDK 21.
 * This is the actual version compiled against JDK 21.
 */
public final class VirtualThreadDelegate {

    public static ExecutorService newVirtualThreadExecutor(ThreadConfigurator configurator) {
        
        return Executors.newThreadPerTaskExecutor(
                r -> {
                    Thread.Builder threadBuilder = Thread.ofVirtual();
                    Thread t = threadBuilder.unstarted(r);
                    configurator.configure(t);
                    return t;
                });
    }

    private VirtualThreadDelegate() {
    }
}