package io.temporal.internal.worker;

import io.temporal.api.worker.v1.WorkerHeartbeat;
import io.temporal.api.workflowservice.v1.RecordWorkerHeartbeatRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
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
  private final Set<String> unimplementedNamespaces = new HashSet<>();

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
      if (unimplementedNamespaces.contains(namespace)) {
        return;
      }
      namespaceWorkers.compute(
          namespace,
          (ns, existing) -> {
            if (existing != null && !existing.isShutdown()) {
              existing.registerWorker(workerInstanceKey, callback);
              return existing;
            }
            SharedNamespaceWorker nsWorker =
                new SharedNamespaceWorker(this, service, ns, identity, interval);
            nsWorker.registerWorker(workerInstanceKey, callback);
            return nsWorker;
          });
    }
  }

  /** Unregister a worker. Stops the namespace worker if no workers remain for that namespace. */
  public void unregisterWorker(String namespace, String workerInstanceKey) {
    synchronized (lock) {
      SharedNamespaceWorker nsWorker = namespaceWorkers.get(namespace);
      if (nsWorker == null) {
        return;
      }
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
   * Called from the scheduler thread when the server returns UNIMPLEMENTED. Uses
   * scheduler.shutdown() (graceful) instead of shutdownNow() to avoid interrupting the
   * currently-executing tick, and skips awaitTermination since we're on the scheduler thread
   * itself.
   */
  void markNamespaceUnimplemented(String namespace) {
    synchronized (lock) {
      unimplementedNamespaces.add(namespace);
      SharedNamespaceWorker nsWorker = namespaceWorkers.remove(namespace);
      if (nsWorker != null) {
        nsWorker.stopScheduling();
      }
    }
  }

  /**
   * Handles heartbeating for all workers in a specific namespace. Each instance owns its own
   * scheduler thread and callback map.
   */
  static class SharedNamespaceWorker {
    private final HeartbeatManager manager;
    private final WorkflowServiceStubs service;
    private final String namespace;
    private final String identity;
    private final ConcurrentHashMap<String, Supplier<WorkerHeartbeat>> callbacks =
        new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    SharedNamespaceWorker(
        HeartbeatManager manager,
        WorkflowServiceStubs service,
        String namespace,
        String identity,
        Duration interval) {
      this.manager = manager;
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

    /** Stops scheduling new ticks. Safe to call from the scheduler thread itself. */
    void stopScheduling() {
      scheduler.shutdown();
    }

    /** Full shutdown from an external thread. Interrupts in-flight work and waits. */
    void shutdown() {
      scheduler.shutdownNow();
      try {
        scheduler.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private void heartbeatTick() {
      if (callbacks.isEmpty()) return;

      List<WorkerHeartbeat> heartbeats = new ArrayList<>();
      for (Map.Entry<String, Supplier<WorkerHeartbeat>> entry : callbacks.entrySet()) {
        try {
          heartbeats.add(entry.getValue().get());
        } catch (Exception e) {
          log.warn(
              "Failed to build heartbeat for worker {} in namespace {}",
              entry.getKey(),
              namespace,
              e);
        }
      }

      if (heartbeats.isEmpty()) return;

      try {
        service
            .blockingStub()
            .recordWorkerHeartbeat(
                RecordWorkerHeartbeatRequest.newBuilder()
                    .setNamespace(namespace)
                    .setIdentity(identity)
                    .addAllWorkerHeartbeat(heartbeats)
                    .build());
      } catch (io.grpc.StatusRuntimeException e) {
        if (e.getStatus().getCode() == io.grpc.Status.Code.UNIMPLEMENTED) {
          log.warn(
              "Server does not support worker heartbeats for namespace {}, disabling", namespace);
          manager.markNamespaceUnimplemented(namespace);
          return;
        }
        log.warn("Failed to send worker heartbeat for namespace {}", namespace, e);
      } catch (Exception e) {
        log.warn("Failed to send worker heartbeat for namespace {}", namespace, e);
      }
    }
  }
}
