/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.statemachines;

import com.google.common.base.Charsets;
import com.google.common.io.CharSink;
import com.google.common.io.Files;
import io.temporal.workflow.Functions;
import io.temporal.workflow.WorkflowTest;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandsGeneratePlantUMLStateDiagrams {

  private static final Logger log = LoggerFactory.getLogger(WorkflowTest.class);

  @Test
  public void plantUML() {
    generate(ActivityStateMachine.class);
    generate(TimerStateMachine.class);
    generate(SignalExternalStateMachine.class);
    generate(CancelExternalStateMachine.class);
    generate(UpsertSearchAttributesStateMachine.class);
    generate(ChildWorkflowStateMachine.class);
    generate(CompleteWorkflowStateMachine.class);
    generate(FailWorkflowStateMachine.class);
    generate(CancelWorkflowStateMachine.class);
    generate(ContinueAsNewWorkflowStateMachine.class);
    generate(WorkflowTaskStateMachine.class);
    generate(SideEffectStateMachine.class);
    generate(MutableSideEffectStateMachine.class);
    generate(LocalActivityStateMachine.class);
    generate(VersionStateMachine.class);
  }

  private void generate(Class<?> commandClass) {
    Functions.Func<String> generator;
    try {
      Field definition = commandClass.getField("STATE_MACHINE_DEFINITION");
      definition.setAccessible(true);
      Method method = definition.getType().getDeclaredMethod("asPlantUMLStateDiagram");
      Object instance = definition.get(null);
      generator =
          () -> {
            try {
              return (String) method.invoke(instance);
            } catch (IllegalAccessException | InvocationTargetException e) {
              throw new RuntimeException("Cannot generate from " + commandClass.getName(), e);
            }
          };
    } catch (NoSuchMethodException | NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException("Cannot generate from " + commandClass.getName(), e);
    }
    writeToFile("main", commandClass, generator.apply());
  }

  static void writeToFile(String prefix, Class<?> type, String diagram) {
    String projectPath = System.getProperty("user.dir");
    String relativePath = type.getName().replace(".", File.separator);
    String fullRelativePath =
        ("src/" + prefix + "/java/" + relativePath).replace("/", File.separator);
    String diagramFile =
        (projectPath + "/" + fullRelativePath).replace("/", File.separator) + ".puml";
    File file = new File(diagramFile);
    CharSink sink = Files.asCharSink(file, Charsets.UTF_8);
    File licenseFile = new File(projectPath + File.separator + "license-header.txt");
    StringBuilder content = new StringBuilder();
    try {
      List<String> license = Files.readLines(licenseFile, Charsets.UTF_8);
      for (String licenseLine : license) {
        content.append("`" + licenseLine + "\n");
      }
      content.append("\n");
      content.append("` PlantUML <plantuml.com> State Diagram.\n");
      content.append(
          "` Generated from "
              + fullRelativePath
              + ".java\n` by "
              + CommandsGeneratePlantUMLStateDiagrams.class.getName()
              + ".\n");
      content.append("\n\n");
      content.append(diagram);
      sink.write(content);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    System.err.println(
        "Look at the generated diagram for the missing state transition: "
            + diagramFile
            + ".\nDon't check this file in. Fix the unit test and delete the file.");
  }
}
