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
import io.temporal.workflowcheck.WorkflowCheck;
import java.util.Date;

@WorkflowInterface
public interface Suppression {
  @WorkflowMethod
  void suppression();

  class SuppressionImpl implements Suppression {
    @Override
    public void suppression() {
      // INVALID: Indirect invalid call
      //   * class: io/temporal/workflowcheck/testdata/Suppression$SuppressionImpl
      //   * method: suppression()V
      //   * accessedClass: io/temporal/workflowcheck/testdata/Suppression$SuppressionImpl
      //   * accessedMember: badThing()V
      //   * accessedCauseClass: java/util/Date
      //   * accessedCauseMethod: <init>()V
      badThing();

      // Suppressed
      badThingSuppressed();

      // INVALID: Indirect invalid call after suppression
      //   * class: io/temporal/workflowcheck/testdata/Suppression$SuppressionImpl
      //   * method: suppression()V
      //   * accessedClass: io/temporal/workflowcheck/testdata/Suppression$SuppressionImpl
      //   * accessedMember: badThing()V
      //   * accessedCauseClass: java/util/Date
      //   * accessedCauseMethod: <init>()V
      badThing();

      // INVALID: Partially suppressed
      //   * class: io/temporal/workflowcheck/testdata/Suppression$SuppressionImpl
      //   * method: suppression()V
      //   * accessedClass: io/temporal/workflowcheck/testdata/Suppression$SuppressionImpl
      //   * accessedMember: badThingPartiallySuppressed()V
      //   * accessedCauseClass: io/temporal/workflowcheck/testdata/Suppression$SuppressionImpl
      //   * accessedCauseMethod: badThing()V
      badThingPartiallySuppressed();

      // Suppress all warnings
      WorkflowCheck.suppressWarnings();
      badThing();
      new Date();
      WorkflowCheck.restoreWarnings();

      // Suppress only warnings for badThing
      WorkflowCheck.suppressWarnings("badThing");
      badThing();
      // INVALID: Not suppressed
      //   * class: io/temporal/workflowcheck/testdata/Suppression$SuppressionImpl
      //   * method: suppression()V
      //   * accessedClass: java/util/Date
      //   * accessedMember: <init>()V
      new Date();
      WorkflowCheck.restoreWarnings();

      // Suppress only warnings for date init
      WorkflowCheck.suppressWarnings("Date.<init>");
      // INVALID: Not suppressed
      //   * class: io/temporal/workflowcheck/testdata/Suppression$SuppressionImpl
      //   * method: suppression()V
      //   * accessedClass: io/temporal/workflowcheck/testdata/Suppression$SuppressionImpl
      //   * accessedMember: badThing()V
      //   * accessedCauseClass: java/util/Date
      //   * accessedCauseMethod: <init>()V
      badThing();
      new Date();
      WorkflowCheck.restoreWarnings();

      // Suppress nested
      WorkflowCheck.suppressWarnings("Date.<init>");
      WorkflowCheck.suppressWarnings("badThing");
      badThing();
      new Date();
      WorkflowCheck.restoreWarnings();
      WorkflowCheck.restoreWarnings();

      // spotless:off
      // LOG: WARNING - 1 warning suppression(s) not restored in io/temporal/workflowcheck/testdata/Suppression$SuppressionImpl.suppression
      WorkflowCheck.suppressWarnings("never-restored");
      // spotless:on

      // spotless:off
      // LOG: WARNING - WorkflowCheck.suppressWarnings call not using string literal at io/temporal/workflowcheck/testdata/Suppression$SuppressionImpl.suppression (Suppression.java:112)
      String warningVar = "not-literal";
      WorkflowCheck.suppressWarnings(warningVar);
      // spotless:on
    }

    public static void badThing() {
      new Date();
    }

    @WorkflowCheck.SuppressWarnings
    private static void badThingSuppressed() {
      new Date();
    }

    @WorkflowCheck.SuppressWarnings(invalidMembers = "Date.<init>")
    private static void badThingPartiallySuppressed() {
      new Date();
      badThing();
    }
  }

  @WorkflowCheck.SuppressWarnings
  class SuppressionImpl2 implements Suppression {
    @Override
    public void suppression() {
      SuppressionImpl.badThing();
      new Date();
    }
  }

  // We just added another param here to confirm annotation array handling
  @WorkflowCheck.SuppressWarnings(invalidMembers = {"badThing", "some-other-param"})
  class SuppressionImpl3 implements Suppression {
    @Override
    public void suppression() {
      SuppressionImpl.badThing();
      // INVALID: Not suppressed
      //   * class: io/temporal/workflowcheck/testdata/Suppression$SuppressionImpl3
      //   * method: suppression()V
      //   * accessedClass: java/util/Date
      //   * accessedMember: <init>()V
      new Date();
    }
  }

  @WorkflowCheck.SuppressWarnings(invalidMembers = "Date.<init>")
  class SuppressionImpl4 implements Suppression {
    @Override
    public void suppression() {
      // INVALID: Not suppressed
      //   * class: io/temporal/workflowcheck/testdata/Suppression$SuppressionImpl4
      //   * method: suppression()V
      //   * accessedClass: io/temporal/workflowcheck/testdata/Suppression$SuppressionImpl
      //   * accessedMember: badThing()V
      //   * accessedCauseClass: java/util/Date
      //   * accessedCauseMethod: <init>()V
      SuppressionImpl.badThing();
      new Date();
    }
  }
}
