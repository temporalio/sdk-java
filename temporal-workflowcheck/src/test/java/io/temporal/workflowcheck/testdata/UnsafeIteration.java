package io.temporal.workflowcheck.testdata;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.*;
import java.util.stream.Stream;

@WorkflowInterface
public interface UnsafeIteration {
  @WorkflowMethod
  void unsafeIteration();

  class UnsafeIterationImpl implements UnsafeIteration {
    @Override
    @SuppressWarnings("all")
    public void unsafeIteration() {
      // INVALID: Set iteration
      //   * class: io/temporal/workflowcheck/testdata/UnsafeIteration$UnsafeIterationImpl
      //   * method: unsafeIteration()V
      //   * accessedClass: java/util/Set
      //   * accessedMember: iterator()Ljava/util/Iterator;
      for (Map.Entry<String, String> kv : Collections.singletonMap("a", "b").entrySet()) {
        kv.getKey();
      }

      Set<Map.Entry<String, String>> sortedMapEntries =
          new TreeMap<>(Collections.singletonMap("a", "b")).entrySet();
      // INVALID: Set iteration, sadly even if the map is deterministic
      //   * class: io/temporal/workflowcheck/testdata/UnsafeIteration$UnsafeIterationImpl
      //   * method: unsafeIteration()V
      //   * accessedClass: java/util/Set
      //   * accessedMember: iterator()Ljava/util/Iterator;
      for (Map.Entry<String, String> kv : sortedMapEntries) {
        kv.getKey();
      }

      Set<String> mySet = new HashSet<>(2);
      mySet.add("a");
      mySet.add("b");

      // SortedSet iteration is safe
      for (String v : new TreeSet<>(mySet)) {
        v.length();
      }

      // So is LinkedHashSet
      for (String v : new LinkedHashSet<>(mySet)) {
        v.length();
      }

      // ArrayDeque is safe
      for (String v : new ArrayDeque<>(mySet)) {
        v.length();
      }

      // Most streams are safe, except for sets
      Stream.of("a", "b");
      Arrays.asList("a", "b").stream();
      // INVALID: Set streams
      //   * class: io/temporal/workflowcheck/testdata/UnsafeIteration$UnsafeIterationImpl
      //   * method: unsafeIteration()V
      //   * accessedClass: java/util/Set
      //   * accessedMember: stream()Ljava/util/stream/Stream;
      mySet.stream().forEach(a -> {});
    }
  }
}
