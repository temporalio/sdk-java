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

package io.temporal.common.metadata;

import static org.junit.Assert.*;

import com.google.common.collect.ImmutableSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class POJOWorkflowImplMetadataTest {
  @Test
  public void testMetadataD() {
    Set<String> wExpected = ImmutableSet.of("AM_C_bb", "AM_E_bb");
    Set<String> qExpected = ImmutableSet.of("b");
    Set<String> sExpected = ImmutableSet.of("a", "c", "d");

    Set<String> wTypes = new HashSet<>();
    Set<String> qTypes = new HashSet<>();
    Set<String> sTypes = new HashSet<>();
    POJOWorkflowImplMetadata implMetadata = POJOWorkflowImplMetadata.newInstance(DImpl.class);
    for (POJOWorkflowInterfaceMetadata workflowInterface : implMetadata.getWorkflowInterfaces()) {
      for (POJOWorkflowMethodMetadata methodMetadata : workflowInterface.getMethodsMetadata()) {
        String name = methodMetadata.getName();
        WorkflowMethodType type = methodMetadata.getType();
        switch (type) {
          case WORKFLOW:
            wTypes.add(name);
            break;
          case QUERY:
            qTypes.add(name);
            break;
          case SIGNAL:
            sTypes.add(name);
            break;
          default:
            throw new IllegalArgumentException("Unknown type: " + type);
        }
      }
    }

    assertEquals(wExpected, wTypes);
    assertEquals(sExpected, sTypes);
    assertEquals(qExpected, qTypes);
  }

  /**
   * Extending an implementation with a completely irrelevant non-workflow interface should not
   * cause any changes. D and DWithAdditionalInterface should give the same final metadata.
   */
  @Test
  public void testMetadataDWithAdditionalInterface() {
    Set<String> wExpected = ImmutableSet.of("AM_C_bb", "AM_E_bb");
    Set<String> qExpected = ImmutableSet.of("b");
    Set<String> sExpected = ImmutableSet.of("a", "c", "d");

    Set<String> wTypes = new HashSet<>();
    Set<String> qTypes = new HashSet<>();
    Set<String> sTypes = new HashSet<>();
    POJOWorkflowImplMetadata implMetadata =
        POJOWorkflowImplMetadata.newInstance(DWithAdditionalInterface.class);
    for (POJOWorkflowInterfaceMetadata workflowInterface : implMetadata.getWorkflowInterfaces()) {
      for (POJOWorkflowMethodMetadata methodMetadata : workflowInterface.getMethodsMetadata()) {
        String name = methodMetadata.getName();
        WorkflowMethodType type = methodMetadata.getType();
        switch (type) {
          case WORKFLOW:
            wTypes.add(name);
            break;
          case QUERY:
            qTypes.add(name);
            break;
          case SIGNAL:
            sTypes.add(name);
            break;
          default:
            throw new IllegalArgumentException("Unknown type: " + type);
        }
      }
    }

    assertEquals(wExpected, wTypes);
    assertEquals(sExpected, sTypes);
    assertEquals(qExpected, qTypes);

    List<POJOWorkflowInterfaceMetadata> workflowInterfaces = implMetadata.getWorkflowInterfaces();
    workflowInterfaces.forEach(
        metadata ->
            assertNotEquals(
                "O is not a workflow interface and shouldn't be returned",
                metadata.getInterfaceClass(),
                POJOWorkflowInterfaceMetadataTest.O.class));
  }

  @Test
  public void testNonPublicInterface() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> POJOWorkflowImplMetadata.newInstance(GImpl.class));
    assertTrue(
        e.getMessage(),
        e.getMessage().contains("Interface with @WorkflowInterface annotation must be public"));
  }

  @Test
  public void testWorkflowImplWithDuplicatedWorkflowTypes() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> POJOWorkflowImplMetadata.newInstance(DuplicatedName1Impl.class));
    assertTrue(e.getMessage(), e.getMessage().contains("bb()"));

    e =
        assertThrows(
            IllegalArgumentException.class,
            () -> POJOWorkflowImplMetadata.newInstance(DuplicatedName2Impl.class));
    assertTrue(e.getMessage(), e.getMessage().contains("bb()"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNotImplementingAnyWorkflowInterface() {
    POJOWorkflowImplMetadata.newInstance(NoWorkflowImpl.class);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNotImplementingAnyNonEmptyWorkflowInterface() {
    POJOWorkflowImplMetadata.newInstance(EmptyImpl.class);
  }

  static class GImpl implements POJOWorkflowInterfaceMetadataTest.G {
    @Override
    public void g() {}
  }

  static class DImpl
      implements POJOWorkflowInterfaceMetadataTest.D, POJOWorkflowInterfaceMetadataTest.E {

    @Override
    public void a() {}

    @Override
    public String b() {
      throw new UnsupportedOperationException("unimplemented");
    }

    @Override
    public void c() {}

    @Override
    public void bb() {}

    @Override
    public void d() {}
  }

  static class DWithAdditionalInterface extends DImpl
      implements POJOWorkflowInterfaceMetadataTest.O {
    @Override
    public void someMethod() {}
  }

  static class DuplicatedName1Impl implements POJOWorkflowInterfaceMetadataTest.DE {

    @Override
    public void a() {}

    @Override
    public String b() {
      throw new UnsupportedOperationException("unimplemented");
    }

    @Override
    public void c() {}

    @Override
    public void bb() {}

    @Override
    public void d() {}
  }

  static class DuplicatedName2Impl
      implements POJOWorkflowInterfaceMetadataTest.F, POJOWorkflowInterfaceMetadataTest.C {

    @Override
    public void a() {}

    @Override
    public String b() {
      throw new UnsupportedOperationException("unimplemented");
    }

    @Override
    public void c() {}

    @Override
    public void bb() {}

    @Override
    public void f() {}
  }

  static class EmptyImpl implements POJOWorkflowInterfaceMetadataTest.Empty {
    public void foo() {}
  }

  static class NoWorkflowImpl implements POJOWorkflowInterfaceMetadataTest.A {

    @Override
    public void a() {}
  }
}
