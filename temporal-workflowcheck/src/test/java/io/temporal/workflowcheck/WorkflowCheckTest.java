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

package io.temporal.workflowcheck;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkflowCheckTest {
  static {
    try (InputStream is =
        WorkflowCheckTest.class.getClassLoader().getResourceAsStream("logging.properties")) {
      LogManager.getLogManager().readConfiguration(is);
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final LoggingCaptureHandler classInfoVisitorLogs = new LoggingCaptureHandler();

  @Before
  public void beforeEach() {
    ClassInfoVisitor.logger.addHandler(classInfoVisitorLogs);
  }

  @After
  public void afterEach() {
    Logger.getLogger(ClassInfoVisitor.class.getName()).removeHandler(classInfoVisitorLogs);
  }

  @Test
  public void testWorkflowCheck() throws IOException {
    // Load properties
    Properties configProps = new Properties();
    try (InputStream is = getClass().getResourceAsStream("testdata/workflowcheck.properties")) {
      configProps.load(is);
    }
    // Collect infos
    Config config = Config.fromProperties(Config.defaultProperties(), configProps);
    List<ClassInfo> infos =
        new WorkflowCheck(config).findWorkflowClasses(System.getProperty("java.class.path"));
    for (ClassInfo info : infos) {
      info.methods.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              entry -> {
                for (ClassInfo.MethodInfo method : entry.getValue()) {
                  if (method.workflowImpl != null) {
                    System.out.println(Printer.methodText(info, entry.getKey(), method));
                  }
                }
              });
    }

    // Collect actual/expected lists (we accept perf penalty of not being sets)
    List<InvalidMemberAccessAssertion> actual = InvalidMemberAccessAssertion.fromClassInfos(infos);
    SourceAssertions expected = SourceAssertions.fromTestSource();

    // Check differences in both directions
    List<InvalidMemberAccessAssertion> diff = new ArrayList<>(actual);
    diff.removeAll(expected.invalidAccesses);
    for (InvalidMemberAccessAssertion v : diff) {
      fail("Unexpected invalid access: " + v);
    }
    diff = new ArrayList<>(expected.invalidAccesses);
    diff.removeAll(actual);
    for (InvalidMemberAccessAssertion v : diff) {
      fail("Missing expected invalid call: " + v);
    }

    // Check that all logs are present
    List<LogRecord> actualLogs = classInfoVisitorLogs.collectRecords();
    for (LogAssertion expectedLog : expected.logs) {
      assertTrue(
          "Cannot find " + expectedLog.level + " log with message: " + expectedLog.message,
          actualLogs.stream()
              .anyMatch(
                  actualLog ->
                      actualLog.getLevel().equals(expectedLog.level)
                          && classInfoVisitorLogs
                              .getFormatter()
                              .formatMessage(actualLog)
                              .equals(expectedLog.message)));
    }
  }

  private static class SourceAssertions {
    private static final String[] SOURCE_FILES =
        new String[] {
          "io/temporal/workflowcheck/testdata/BadCalls.java",
          "io/temporal/workflowcheck/testdata/Configured.java",
          "io/temporal/workflowcheck/testdata/Suppression.java",
          "io/temporal/workflowcheck/testdata/UnsafeIteration.java"
        };

    static SourceAssertions fromTestSource() {
      List<InvalidMemberAccessAssertion> invalidAccesses = new ArrayList<>();
      List<LogAssertion> logAsserts = new ArrayList<>();
      for (String resourcePath : SOURCE_FILES) {
        String[] fileParts = resourcePath.split("/");
        String fileName = fileParts[fileParts.length - 1];
        // Load lines
        List<String> lines;
        try (InputStream is =
            Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
          assertNotNull(is);
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
          lines = reader.lines().collect(Collectors.toList());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }

        // Add asserts
        invalidAccesses.addAll(InvalidMemberAccessAssertion.fromJavaLines(fileName, lines));
        logAsserts.addAll(LogAssertion.fromJavaLines(lines));
      }
      return new SourceAssertions(invalidAccesses, logAsserts);
    }

    final List<InvalidMemberAccessAssertion> invalidAccesses;
    final List<LogAssertion> logs;

    private SourceAssertions(
        List<InvalidMemberAccessAssertion> invalidAccesses, List<LogAssertion> logs) {
      this.invalidAccesses = invalidAccesses;
      this.logs = logs;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      SourceAssertions that = (SourceAssertions) o;
      return Objects.equals(invalidAccesses, that.invalidAccesses)
          && Objects.equals(logs, that.logs);
    }

    @Override
    public int hashCode() {
      return Objects.hash(invalidAccesses, logs);
    }
  }

  private static class InvalidMemberAccessAssertion {
    static List<InvalidMemberAccessAssertion> fromClassInfos(List<ClassInfo> infos) {
      List<InvalidMemberAccessAssertion> assertions = new ArrayList<>();
      for (ClassInfo info : infos) {
        for (Map.Entry<String, List<ClassInfo.MethodInfo>> methods : info.methods.entrySet()) {
          for (ClassInfo.MethodInfo method : methods.getValue()) {
            // Only invalid workflow impls with invalid accesses
            if (method.workflowImpl != null && method.invalidMemberAccesses != null) {
              for (ClassInfo.MethodInvalidMemberAccessInfo access : method.invalidMemberAccesses) {
                // Find first cause
                ClassInfo.MethodInvalidMemberAccessInfo causeAccess = null;
                if (access.resolvedInvalidMethod != null
                    && access.resolvedInvalidMethod.invalidMemberAccesses != null) {
                  causeAccess = access.resolvedInvalidMethod.invalidMemberAccesses.get(0);
                }
                assertions.add(
                    new InvalidMemberAccessAssertion(
                        info.fileName,
                        Objects.requireNonNull(access.line),
                        info.name,
                        methods.getKey() + method.descriptor,
                        access.className,
                        access.operation
                                == ClassInfo.MethodInvalidMemberAccessInfo.Operation.METHOD_CALL
                            ? access.memberName + access.memberDescriptor
                            : access.memberName,
                        causeAccess == null ? null : causeAccess.className,
                        causeAccess == null
                            ? null
                            : causeAccess.operation
                                    == ClassInfo.MethodInvalidMemberAccessInfo.Operation.METHOD_CALL
                                ? causeAccess.memberName + causeAccess.memberDescriptor
                                : causeAccess.memberName));
              }
            }
          }
        }
      }
      return assertions;
    }

    static List<InvalidMemberAccessAssertion> fromJavaLines(String fileName, List<String> lines) {
      List<InvalidMemberAccessAssertion> assertions = new ArrayList<>();
      for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
        String line = lines.get(lineIdx).trim();
        // Confirm INVALID
        if (!line.startsWith("// INVALID")) {
          continue;
        }
        // Collect indented bullets
        Map<String, String> bullets = new HashMap<>(6);
        while (lines.get(lineIdx + 1).trim().startsWith("//   * ")) {
          lineIdx++;
          line = lines.get(lineIdx).substring(lines.get(lineIdx).indexOf("/") + 7);
          int colonIndex = line.indexOf(":");
          assertTrue(colonIndex > 0);
          bullets.put(line.substring(0, colonIndex).trim(), line.substring(colonIndex + 1).trim());
        }
        assertions.add(
            new InvalidMemberAccessAssertion(
                fileName,
                lineIdx + 2,
                Objects.requireNonNull(bullets.get("class")),
                Objects.requireNonNull(bullets.get("method")),
                Objects.requireNonNull(bullets.get("accessedClass")),
                Objects.requireNonNull(bullets.get("accessedMember")),
                bullets.get("accessedCauseClass"),
                bullets.get("accessedCauseMethod")));
      }
      return assertions;
    }

    final String fileName;
    final int line;
    final String className;
    final String member;
    final String accessedClass;
    final String accessedMember;
    // Cause info can be null
    @Nullable final String accessedCauseClass;
    @Nullable final String accessedCauseMethod;

    private InvalidMemberAccessAssertion(
        String fileName,
        int line,
        String className,
        String member,
        String accessedClass,
        String accessedMember,
        @Nullable String accessedCauseClass,
        @Nullable String accessedCauseMethod) {
      this.fileName = fileName;
      this.line = line;
      this.className = className;
      this.member = member;
      this.accessedClass = accessedClass;
      this.accessedMember = accessedMember;
      this.accessedCauseClass = accessedCauseClass;
      this.accessedCauseMethod = accessedCauseMethod;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      InvalidMemberAccessAssertion that = (InvalidMemberAccessAssertion) o;
      return line == that.line
          && Objects.equals(fileName, that.fileName)
          && Objects.equals(className, that.className)
          && Objects.equals(member, that.member)
          && Objects.equals(accessedClass, that.accessedClass)
          && Objects.equals(accessedMember, that.accessedMember)
          && Objects.equals(accessedCauseClass, that.accessedCauseClass)
          && Objects.equals(accessedCauseMethod, that.accessedCauseMethod);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          fileName,
          line,
          className,
          member,
          accessedClass,
          accessedMember,
          accessedCauseClass,
          accessedCauseMethod);
    }

    @Override
    public String toString() {
      return "InvalidMemberAccessAssertion{"
          + "fileName='"
          + fileName
          + '\''
          + ", line="
          + line
          + ", className='"
          + className
          + '\''
          + ", member='"
          + member
          + '\''
          + ", accessedClass='"
          + accessedClass
          + '\''
          + ", accessedMember='"
          + accessedMember
          + '\''
          + ", accessedCauseClass='"
          + accessedCauseClass
          + '\''
          + ", accessedCauseMethod='"
          + accessedCauseMethod
          + '\''
          + '}';
    }
  }

  private static class LogAssertion {
    static List<LogAssertion> fromJavaLines(List<String> lines) {
      return lines.stream()
          .map(String::trim)
          .filter(line -> line.startsWith("// LOG: "))
          .map(
              line -> {
                int dashIndex = line.indexOf('-');
                assertTrue(dashIndex > 0);
                return new LogAssertion(
                    Level.parse(line.substring(8, dashIndex).trim()),
                    line.substring(dashIndex + 1).trim());
              })
          .collect(Collectors.toList());
    }

    final Level level;
    final String message;

    private LogAssertion(Level level, String message) {
      this.level = level;
      this.message = message;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      LogAssertion that = (LogAssertion) o;
      return Objects.equals(level, that.level) && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
      return Objects.hash(level, message);
    }
  }
}
