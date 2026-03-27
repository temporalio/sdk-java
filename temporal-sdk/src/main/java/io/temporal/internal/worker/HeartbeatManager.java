package io.temporal.internal.worker;

import com.google.common.base.Preconditions;
import io.temporal.api.worker.v1.WorkerHeartbeat;
import io.temporal.api.workflowservice.v1.RecordWorkerHeartbeatRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages periodic worker heartbeat RPCs. Routes workers to per-namespace {@link
 * SharedNamespaceWorker} instances, each with its own scheduler.
 */
public class HeartbeatManager {
  private static final Logger log = LoggerFactory.getLogger(HeartbeatManager.class);

  private final WorkflowServiceStubs service;
  private final String identity;
  private final Duration interval;
  private final Map<String, SharedNamespaceWorker> namespaceWorkers = new HashMap<>();

  private final Object lock = new Object();

  public HeartbeatManager(WorkflowServiceStubs service, String identity, Duration interval) {
    this.service = service;
    this.identity = identity;
    this.interval = interval;
  }

  /**
   * Register a worker's heartbeat callback. Creates a per-namespace SharedNamespaceWorker if this
   * is the first worker for the given namespace.
   */
  public void registerWorker(
      String namespace, String workerInstanceKey, Supplier<WorkerHeartbeat> callback) {
    synchronized (lock) {
      namespaceWorkers.compute(
          namespace,
          (ns, existing) -> {
            if (existing != null && !existing.isShutdown()) {
              existing.registerWorker(workerInstanceKey, callback);
              return existing;
            }
            SharedNamespaceWorker nsWorker =
                new SharedNamespaceWorker(service, ns, identity, interval);
            nsWorker.registerWorker(workerInstanceKey, callback);
            return nsWorker;
          });
    }
  }

  /** Unregister a worker. Stops the namespace worker if no workers remain for that namespace. */
  public void unregisterWorker(String namespace, String workerInstanceKey) {
    synchronized (lock) {
      SharedNamespaceWorker nsWorker = namespaceWorkers.get(namespace);
      Preconditions.checkState(
          nsWorker != null,
          "unregisterWorker called for unknown namespace %s, worker %s",
          namespace,
          workerInstanceKey);
      nsWorker.unregisterWorker(workerInstanceKey);
      if (nsWorker.isEmpty()) {
        nsWorker.shutdown();
        namespaceWorkers.remove(namespace);
      }
    }
  }

  public void shutdown() {
    synchronized (lock) {
      for (SharedNamespaceWorker nsWorker : namespaceWorkers.values()) {
        nsWorker.shutdown();
      }
      namespaceWorkers.clear();
    }
  }

  /**
   * Handles heartbeating for all workers in a specific namespace. Each instance owns its own
   * scheduler thread and callback map.
   */
  static class SharedNamespaceWorker {
    private final WorkflowServiceStubs service;
    private final String namespace;
    private final String identity;
    private final ConcurrentHashMap<String, Supplier<WorkerHeartbeat>> callbacks =
        new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    SharedNamespaceWorker(
        WorkflowServiceStubs service, String namespace, String identity, Duration interval) {
      this.service = service;
      this.namespace = namespace;
      this.identity = identity;
      this.scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "worker-heartbeat-" + namespace);
                t.setDaemon(true);
                return t;
              });
      scheduler.scheduleAtFixedRate(
          this::heartbeatTick, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void registerWorker(String workerInstanceKey, Supplier<WorkerHeartbeat> callback) {
      callbacks.put(workerInstanceKey, callback);
    }

    void unregisterWorker(String workerInstanceKey) {
      callbacks.remove(workerInstanceKey);
    }

    boolean isEmpty() {
      return callbacks.isEmpty();
    }

    boolean isShutdown() {
      return scheduler.isShutdown();
    }

    void shutdown() {
      if (!shuttingDown.compareAndSet(false, true)) return;
      scheduler.shutdown();
      try {
        scheduler.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private void heartbeatTick() {
      if (callbacks.isEmpty()) return;

      try {
        List<WorkerHeartbeat> heartbeats = new ArrayList<>();
        for (Supplier<WorkerHeartbeat> callback : callbacks.values()) {
          heartbeats.add(callback.get());
        }

        if (!heartbeats.isEmpty()) {
          service
              .blockingStub()
              .recordWorkerHeartbeat(
                  RecordWorkerHeartbeatRequest.newBuilder()
                      .setNamespace(namespace)
                      .setIdentity(identity)
                      .addAllWorkerHeartbeat(heartbeats)
                      .build());
        }
      } catch (io.grpc.StatusRuntimeException e) {
        if (e.getStatus().getCode() == io.grpc.Status.Code.UNIMPLEMENTED) {
          log.warn(
              "Server does not support worker heartbeats for namespace {}, disabling", namespace);
          // Only signal shutdown — don't awaitTermination from within the scheduler's own thread
          shuttingDown.set(true);
          scheduler.shutdown();
          return;
        }
        log.warn("Failed to send worker heartbeat for namespace {}", namespace, e);
      } catch (Exception e) {
        log.warn("Failed to send worker heartbeat for namespace {}", namespace, e);
      }
    }
  }
}
