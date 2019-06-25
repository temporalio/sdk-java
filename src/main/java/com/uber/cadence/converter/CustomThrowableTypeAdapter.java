/*
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

package com.uber.cadence.converter;

import com.google.common.collect.ImmutableSet;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CustomThrowableTypeAdapter<T extends Throwable> extends TypeAdapter<T> {
  private static final Logger log = LoggerFactory.getLogger(CustomThrowableTypeAdapter.class);

  /** Used to parse a stack trace line. */
  private static final String TRACE_ELEMENT_REGEXP =
      "((?<className>.*)\\.(?<methodName>.*))\\(((?<fileName>.*?)(:(?<lineNumber>\\d+))?)\\)";

  private static final Pattern TRACE_ELEMENT_PATTERN = Pattern.compile(TRACE_ELEMENT_REGEXP);

  /**
   * Stop emitting stack trace after this line. Makes serialized stack traces more readable and
   * compact as it omits most of framework level code.
   */
  private static final ImmutableSet<String> CUTOFF_METHOD_NAMES =
      ImmutableSet.of(
          "com.uber.cadence.internal.worker.POJOActivityImplementationFactory$POJOActivityImplementation.execute",
          "com.uber.cadence.internal.sync.POJODecisionTaskHandler$POJOWorkflowImplementation.execute");

  private final Gson gson;
  private final TypeAdapterFactory skipPast;

  CustomThrowableTypeAdapter(Gson gson, TypeAdapterFactory skipPast) {
    this.gson = gson;
    this.skipPast = skipPast;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void write(JsonWriter jsonWriter, T throwable) throws IOException {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    StackTraceElement[] trace = throwable.getStackTrace();
    for (StackTraceElement element : trace) {
      pw.println(element);
      String fullMethodName = element.getClassName() + "." + element.getMethodName();
      if (CUTOFF_METHOD_NAMES.contains(fullMethodName)) {
        break;
      }
    }

    // We want to serialize the throwable and its cause separately, so that if the throwable
    // is serializable but the cause is not, we can still serialize them correctly (i.e. we
    // serialize the throwable correctly and convert the cause to a data converter exception).
    Throwable cause = null;
    if (throwable.getCause() != null && throwable.getCause() != throwable) {
      try {
        cause = throwable.getCause();
        Field causeField = Throwable.class.getDeclaredField("cause");
        causeField.setAccessible(true);
        causeField.set(throwable, null);
      } catch (Exception e) {
        log.warn("Failed to clear cause in original throwable.", e);
      }
    }

    JsonObject object;
    try {
      TypeAdapter exceptionTypeAdapter =
          gson.getDelegateAdapter(skipPast, TypeToken.get(throwable.getClass()));
      object = exceptionTypeAdapter.toJsonTree(throwable).getAsJsonObject();
      object.add("class", new JsonPrimitive(throwable.getClass().getName()));
      object.add("stackTrace", new JsonPrimitive(sw.toString()));
    } catch (Throwable e) {
      // In case a throwable is not serializable, we will convert it to a data converter exception.
      // The cause of the data converter exception will indicate why the serialization failed. On
      // the other hand, if the non-serializable throwable contains a cause, we will add it to the
      // suppressed exceptions list.
      DataConverterException ee =
          new DataConverterException("Failure serializing exception: " + throwable.toString(), e);
      if (cause != null) {
        ee.addSuppressed(cause);
        cause = null;
      }

      TypeAdapter<Throwable> exceptionTypeAdapter =
          new CustomThrowableTypeAdapter<>(gson, skipPast);
      object = exceptionTypeAdapter.toJsonTree(ee).getAsJsonObject();
    }

    if (cause != null) {
      TypeAdapter<Throwable> causeTypeAdapter = new CustomThrowableTypeAdapter<>(gson, skipPast);
      try {
        object.add("cause", causeTypeAdapter.toJsonTree(cause));
      } catch (Throwable e) {
        DataConverterException ee =
            new DataConverterException("Failure serializing exception: " + cause.toString(), e);
        ee.setStackTrace(cause.getStackTrace());
        object.add("cause", causeTypeAdapter.toJsonTree(ee));
      }
    }

    TypeAdapter<JsonElement> elementAdapter = gson.getAdapter(JsonElement.class);
    elementAdapter.write(jsonWriter, object);
  }

  @Override
  public T read(JsonReader jsonReader) throws IOException {
    TypeAdapter<JsonElement> elementAdapter = gson.getAdapter(JsonElement.class);
    JsonObject object = elementAdapter.read(jsonReader).getAsJsonObject();
    JsonElement classElement = object.get("class");
    if (classElement != null) {
      String className = classElement.getAsString();
      Class<?> classType;
      try {
        classType = Class.forName(className);
      } catch (ClassNotFoundException e) {
        throw new IOException("Cannot deserialize " + className + " exception", e);
      }
      if (!Throwable.class.isAssignableFrom(classType)) {
        throw new IOException("Expected type that extends Throwable: " + className);
      }

      StackTraceElement[] stackTrace = parseStackTrace(object);
      // This is important. Initially I tried configuring ExclusionStrategy to not
      // deserialize the stackTrace field.
      // But it left it null, which caused Thread.setStackTrace implementation to become
      // silent noop.
      object.add("stackTrace", new JsonArray());
      TypeAdapter exceptionTypeAdapter =
          gson.getDelegateAdapter(skipPast, TypeToken.get(classType));
      Throwable result = (Throwable) exceptionTypeAdapter.fromJsonTree(object);
      result.setStackTrace(stackTrace);
      @SuppressWarnings("unchecked")
      T typedResult = (T) result;
      return typedResult;
    }
    throw new IOException();
  }

  private StackTraceElement[] parseStackTrace(JsonObject object) {
    JsonElement jsonStackTrace = object.get("stackTrace");
    if (jsonStackTrace == null) {
      return new StackTraceElement[0];
    }
    String stackTrace = jsonStackTrace.getAsString();
    if (stackTrace == null || stackTrace.isEmpty()) {
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
}
