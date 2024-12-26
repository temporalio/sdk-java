/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
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
