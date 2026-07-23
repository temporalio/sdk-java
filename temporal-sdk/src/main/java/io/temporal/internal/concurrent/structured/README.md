# Structured Concurrency

Minimal, non-blocking, "structured concurrency" wrapper for `CompletableFuture`. No executors or 
thread management. Just a wrapper that provides correctness guarantees and convenience.

## Usage

```java
CompletableFuture<List<String>> result =
    TaskScope.withScope( // 1. create a scope
        scope -> {
            scope.attach(startWork()); // 2. add tasks to the scope
            scope.attach(startMoreWork())
                .map(value -> doDownstreamWork(value));

            return scope.awaitAll(); // 3. collect results
        });

result.get();        // 4. get results a usual
// or
result.cancel(true); // 5. cancel (propagates to all child tasks)
```

What it provides:

1. A `withScope` boundary whose result waits for every attached task to settle before completing
   normally or exceptionally, so no task outlives the scope.
2. Fan-in over a group of async operations: `awaitAll` (results in order), `awaitAll(transformer)`
   (reshape the group), and `awaitAllSettled` (per-task `Result`).
3. Fail-fast: the first failure completes the scope's result with that error and cancels the rest.
4. Scope cancellation (`cancelAll`, or cancelling the returned future) propagates to every attached
   task; task-chain cancellation propagates from parent stages to derived stages.
5. Cooperative cancellation via `CancellationToken`.
6. `TaskScope` methods return a `TaskChain`, not raw task handles, so tasks cannot be joined,
   cancelled, or converted back to futures outside the owning scope.

## Execution model

`TaskScope` is an ownership boundary, not an executor. It owns task lifetime, cancellation, result
collection, and the guarantee that scoped work settles before the returned future does. It does not fork
new threads, use executors under the hood, or create any virtual thread abstractions. It's just a wrapper
over CompletableFuture.

## Why

```java
List<Batch<Payload>> batches = getPayloadBatches();
CompletableFuture<List<IndexedPayload>> result =
    TaskScope.withScope( scope -> {
        StorageDriverStoreContext context = new StorageDriverStoreContextImpl(target, scope.token());

        for (Batch<Payload> batch : batches) {
            scope
                .attach(batch.driver.store(context, batch.values()))
                .map(claims -> createReferencePayloads(batch, claims));
        }
        return scope.awaitAll(ListUtils::flatten);
    });

result.cancel(true);
```

The equivalent code using `CompletableFuture` directly has to rebuild the same ownership and cancellation rules by hand:

```java
List<Batch<Payload>> batches = getPayloadBatches();
CancelSource cancellation = new CancelSource();
StorageDriverStoreContext context =
    new StorageDriverStoreContextImpl(target, cancellation.token());

List<CompletableFuture<?>> upstream = new ArrayList<>();
List<CompletableFuture<List<IndexedPayload>>> downstream = new ArrayList<>();
CompletableFuture<List<IndexedPayload>> result = new CompletableFuture<>();

for (Batch<Payload> batch : batches) {
    CompletableFuture<List<StorageDriverClaim>> storeFuture =
        batch.driver.store(context, batch.values());
    upstream.add(storeFuture);

    CompletableFuture<List<IndexedPayload>> downstreamFuture =
        storeFuture.thenApply(claims -> createReferencePayloads(batch, claims));
    downstream.add(downstreamFuture);
}

if (downstream.isEmpty()) {
    result.complete(Collections.emptyList());
    return result;
}

AtomicBoolean completed = new AtomicBoolean(false);
AtomicInteger remaining = new AtomicInteger(downstream.size());

Runnable cancelTracked =
    () -> {
        cancellation.cancel();
        for (CompletableFuture<?> upstreamFuture : upstream) {
            upstreamFuture.cancel(true);
        }
        for (CompletableFuture<?> downstreamFuture : downstream) {
            downstreamFuture.cancel(true);
        }
    };

Supplier<CompletableFuture<Void>> allTrackedSettled =
    () -> {
        List<CompletableFuture<?>> tracked = new ArrayList<>(upstream.size() + downstream.size());
        tracked.addAll(upstream);
        tracked.addAll(downstream);
        return CompletableFuture
            .allOf(tracked.toArray(new CompletableFuture<?>[tracked.size()]))
            .handle((ignored, terminationError) -> null);
    };

for (CompletableFuture<List<IndexedPayload>> downstreamFuture : downstream) {
    downstreamFuture.whenComplete(
            (ignored, err) -> {
                if (err != null) {
                    if (completed.compareAndSet(false, true)) {
                        cancelTracked.run();
                        allTrackedSettled
                            .get()
                            .whenComplete(
                                (unused, terminationError) -> result.completeExceptionally(err));
                    }
                    return;
                }

                if (remaining.decrementAndGet() == 0 && completed.compareAndSet(false, true)) {
                    allTrackedSettled
                        .get()
                        .whenComplete(
                            (unused, terminationError) -> {
                                try {
                                    List<List<IndexedPayload>> values = new ArrayList<>();
                                    for (CompletableFuture<List<IndexedPayload>> completedDownstream : downstream) {
                                        values.add(completedDownstream.join());
                                    }
                                    result.complete(ListUtils.flatten(values));
                                } catch (Throwable resultError) {
                                    result.completeExceptionally(resultError);
                                }
                            });
                }
            });
}

result.whenComplete(
        (ignored, err) -> {
            if (result.isCancelled() && completed.compareAndSet(false, true)) {
                cancelTracked.run();
            }
        });

return result;
```

With direct `CompletableFuture` composition, fan-out/fan-in code usually degrades into:

- ad-hoc cancellation wiring per call site
- separate upstream and downstream tracking so scope cancellation reaches both driver work and derived stages
- manual propagation to cooperative cancellation tokens as well as `CompletableFuture.cancel(true)`
- no built-in parent boundary that owns the child set
- duplicated fail-fast, all-settled, and group-termination orchestration logic
