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

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.common.metadata.testclasses.ActivityInterfaceWithOneNonAnnotatedMethod;
import java.util.ArrayList;
import java.util.Collection;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.modifier.ModifierContributor;
import net.bytebuddy.description.modifier.Ownership;
import net.bytebuddy.description.modifier.SyntheticState;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FixedValue;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class POJOActivityInterfaceMetadataTest {
  @Test
  public void unannotatedActivityMethod() {
    POJOActivityInterfaceMetadata metadata =
        POJOActivityInterfaceMetadata.newInstance(ActivityInterfaceWithOneNonAnnotatedMethod.class);
    assertEquals(1, metadata.getMethodsMetadata().size());
    assertTrue(
        metadata.getMethodsMetadata().stream()
            .anyMatch(m -> m.getMethod().getName().equals("activityMethod")));
  }

  @Test
  @Parameters({
    "false, true, false, false, false",
    "true, false, false, false, false",
    "false, true, true, false, true",
    "true, false, true, true, false"
  })
  public void testSyntheticAndStaticMethods(
      boolean synthetic,
      boolean statik,
      boolean annotated,
      boolean shouldBeConsideredAnActivityMethod,
      boolean shouldThrow)
      throws Throwable {
    Class<?> interfaice = generateActivityInterfaceWithMethod(synthetic, statik, annotated);

    ThrowingRunnable r =
        () -> {
          POJOActivityInterfaceMetadata metadata =
              POJOActivityInterfaceMetadata.newInstance(interfaice);
          assertEquals(
              shouldBeConsideredAnActivityMethod,
              metadata.getMethodsMetadata().stream()
                  .anyMatch(m -> m.getMethod().getName().equals("method")));
        };
    if (shouldThrow) {
      assertThrows(IllegalArgumentException.class, r);
    } else {
      r.run();
    }
  }

  private Class<?> generateActivityInterfaceWithMethod(
      boolean synthetic, boolean statik, boolean annotated) {
    DynamicType.Builder<?> builder =
        new ByteBuddy()
            .makeInterface(ActivityInterfaceWithOneNonAnnotatedMethod.class)
            .name("GeneratedActivityInterface")
            .annotateType(AnnotationDescription.Builder.ofType(ActivityInterface.class).build());
    Collection<ModifierContributor.ForMethod> modifiers = new ArrayList<>();
    modifiers.add(Visibility.PUBLIC);
    if (synthetic) {
      modifiers.add(SyntheticState.SYNTHETIC);
    }
    if (statik) {
      modifiers.add(Ownership.STATIC);
    }

    DynamicType.Builder.MethodDefinition.ParameterDefinition.Initial<?> methodInitial =
        builder.defineMethod("method", String.class, modifiers);
    DynamicType.Builder.MethodDefinition<?> methodDefinition =
        statik ? methodInitial.intercept(FixedValue.value("hi")) : methodInitial.withoutCode();

    if (annotated) {
      methodDefinition =
          methodDefinition.annotateMethod(
              AnnotationDescription.Builder.ofType(ActivityMethod.class).build());
    }

    return methodDefinition.make().load(this.getClass().getClassLoader()).getLoaded();
  }
}
