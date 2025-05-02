package io.temporal.internal.client;

import io.temporal.worker.WorkerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;

public class WorkerFactoryRegistry {
  private final CopyOnWriteArrayList<WorkerFactory> workerFactories = new CopyOnWriteArrayList<>();

  public Iterable<WorkerFactory> workerFactoriesRandomOrder() {
    int count = workerFactories.size();
    if (count > 1) {
      ArrayList<WorkerFactory> result = new ArrayList<>(workerFactories);
      Collections.shuffle(result);
      return result;
    } else {
      return workerFactories;
    }
  }

  public void register(WorkerFactory workerFactory) {
    workerFactories.addIfAbsent(workerFactory);
  }

  public void deregister(WorkerFactory workerFactory) {
    workerFactories.remove(workerFactory);
  }
}
