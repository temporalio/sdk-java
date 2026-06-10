package io.temporal.internal.client;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import com.google.common.collect.Lists;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.WorkerFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.Test;

public class WorkerFactoryRegistryTest {
  /**
   * Covers a situation when one {@link WorkflowClient} used to create several WorkerFactories.
   * {@link WorkerFactoryRegistry#workerFactoriesRandomOrder()} will be used to get an iterator over
   * registered WorkerFactories. The iteration order should be random to allow a roughly even
   * distribution of requests. This test verifies every possible permutation of workers can happen,
   * which we use as a proxy to determine the ordering is sufficiently random.
   */
  @Test
  public void testRandomOrder() {
    // Creating 5 separate factories. Indices in this list will be used to identify ordering of
    // returned random iterables.
    List<WorkerFactory> orderedFactories =
        Stream.of(
                mock(WorkerFactory.class),
                mock(WorkerFactory.class),
                mock(WorkerFactory.class),
                mock(WorkerFactory.class),
                mock(WorkerFactory.class))
            .collect(Collectors.toList());

    WorkerFactoryRegistry workerFactoryRegistry = new WorkerFactoryRegistry();
    orderedFactories.forEach(workerFactoryRegistry::register);

    HashSet<Long> permutationsFound = new HashSet<>();
    // The number of all possible permutations is the factorial of number of elements.
    long expectedPermutationsCount =
        LongStream.rangeClosed(1, orderedFactories.size()).reduce(1, (a, b) -> a * b);

    while (permutationsFound.size() < expectedPermutationsCount) {
      List<WorkerFactory> randomFactories =
          Lists.newArrayList(workerFactoryRegistry.workerFactoriesRandomOrder());
      assertEquals(orderedFactories.size(), randomFactories.size());

      List<Integer> indices = new ArrayList<>(orderedFactories.size());
      for (WorkerFactory factory : randomFactories) {
        int index = orderedFactories.indexOf(factory);
        assertFalse("Factory " + index + " appears twice", indices.contains(index));
        indices.add(index);
      }

      // This number uniquely identifies the current permutation, so that we can ensure all
      // permutations were seen. Written in base-(orderedFactories.size()), the digits are
      // the respective indices of randomFactories in orderedFactories.
      long permutationId = 0;
      for (int index : indices) {
        permutationId *= orderedFactories.size();
        permutationId += index;
      }
      permutationsFound.add(permutationId);
    }
  }
}
