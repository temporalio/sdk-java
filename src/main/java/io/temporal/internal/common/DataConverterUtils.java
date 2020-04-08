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

package io.temporal.internal.common;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataConverterUtils {
  private static final Logger log = LoggerFactory.getLogger(DataConverterUtils.class);

  /**
   * Stop emitting stack trace after this line. Makes serialized stack traces more readable and
   * compact as it omits most of framework level code.
   */
  private static final ImmutableSet<String> CUTOFF_METHOD_NAMES =
      ImmutableSet.of(
          "io.temporal.internal.worker.POJOActivityImplementationFactory$POJOActivityImplementation.execute",
          "io.temporal.internal.sync.POJODecisionTaskHandler$POJOWorkflowImplementation.execute");

  /** Used to parse a stack trace line. */
  private static final String TRACE_ELEMENT_REGEXP =
      "((?<className>.*)\\.(?<methodName>.*))\\(((?<fileName>.*?)(:(?<lineNumber>\\d+))?)\\)";

  private static final Pattern TRACE_ELEMENT_PATTERN = Pattern.compile(TRACE_ELEMENT_REGEXP);

  private static final boolean SETTING_PRIVATE_FIELD_ALLOWED;

  static {
    boolean value = false;
    try {
      Field causeField = Throwable.class.getDeclaredField("cause");
      causeField.setAccessible(true);
      causeField.set(new RuntimeException(), null);
      value = true;
    } catch (IllegalAccessException e) {
    } catch (Exception ex) {
      throw new Error("Unexpected", ex);
    }
    SETTING_PRIVATE_FIELD_ALLOWED = value;
  }

  /** Are JVM permissions allowing setting private fields using reflection? */
  public static boolean isSettingPrivateFieldAllowed() {
    return SETTING_PRIVATE_FIELD_ALLOWED;
  }

  public static String serializeStackTrace(Throwable e) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    StackTraceElement[] trace = e.getStackTrace();
    for (StackTraceElement element : trace) {
      pw.println(element);
      String fullMethodName = element.getClassName() + "." + element.getMethodName();
      if (CUTOFF_METHOD_NAMES.contains(fullMethodName)) {
        break;
      }
    }
    return sw.toString();
  }

  /** Parses stack trace serialized using {@link #serializeStackTrace(Throwable)}. */
  public static StackTraceElement[] parseStackTrace(String stackTrace) {
    if (Strings.isNullOrEmpty(stackTrace)) {
      return new StackTraceElement[0];
    }
    try {
      @SuppressWarnings("StringSplitter")
      String[] lines = stackTrace.split("\r\n|\n");
      StackTraceElement[] result = new StackTraceElement[lines.length];
      for (int i = 0; i < lines.length; i++) {
        result[i] = parseStackTraceElement(lines[i]);
      }
      return result;
    } catch (Exception e) {
      if (log.isWarnEnabled()) {
        log.warn("Failed to parse stack trace: " + stackTrace);
      }
      return new StackTraceElement[0];
    }
  }

  /**
   * See {@link StackTraceElement#toString()} for input specification.
   *
   * @param line line of stack trace.
   * @return StackTraceElement that contains data from that line.
   */
  private static StackTraceElement parseStackTraceElement(String line) {
    Matcher matcher = TRACE_ELEMENT_PATTERN.matcher(line);
    if (!matcher.matches()) {
      return null;
    }
    String declaringClass = matcher.group("className");
    String methodName = matcher.group("methodName");
    String fileName = matcher.group("fileName");
    int lineNumber = 0;
    String lns = matcher.group("lineNumber");
    if (lns != null && lns.length() > 0) {
      try {
        lineNumber = Integer.parseInt(matcher.group("lineNumber"));
      } catch (NumberFormatException e) {
      }
    }
    return new StackTraceElement(declaringClass, methodName, fileName, lineNumber);
  }

  /**
   * We want to serialize the throwable and its cause separately, so that if the throwable is
   * serializable but the cause is not, we can still serialize them correctly (i.e. we serialize the
   * throwable correctly and convert the cause to a data converter exception). If existing cause is
   * not detached due to security policy then null is returned.
   */
  public static Throwable detachCause(Throwable throwable) {
    Throwable cause = null;
    if (isSettingPrivateFieldAllowed()
        && throwable.getCause() != null
        && throwable.getCause() != throwable) {
      try {
        cause = throwable.getCause();
        Field causeField = Throwable.class.getDeclaredField("cause");
        causeField.setAccessible(true);
        causeField.set(throwable, null);
      } catch (Exception e) {
        log.warn("Failed to clear cause in original throwable.", e);
      }
    }
    return cause;
  }

  private DataConverterUtils() {}
}
