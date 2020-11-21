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

package io.temporal.common.context;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.log.Fields;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.logging.LoggerTag;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Support for OpenTracing spans */
public class OpenTracingContextPropagator implements ContextPropagator {

  private static final Logger log = LoggerFactory.getLogger(OpenTracingContextPropagator.class);

  private static final ThreadLocal<SpanContext> currentOpenTracingSpanContext = new ThreadLocal<>();
  private static final ThreadLocal<Span> currentOpenTracingSpan = new ThreadLocal<>();
  private static final ThreadLocal<Scope> currentOpenTracingScope = new ThreadLocal<>();

  public static SpanContext getCurrentOpenTracingSpanContext() {
    return currentOpenTracingSpanContext.get();
  }

  public static void setCurrentOpenTracingSpanContext(SpanContext ctx) {
    if (ctx != null) {
      currentOpenTracingSpanContext.set(ctx);
    }
  }

  @Override
  public String getName() {
    return "OpenTracing";
  }

  @Override
  public Map<String, Payload> serializeContext(Object context) {
    Map<String, Payload> serializedContext = new HashMap<>();
    Map<String, String> contextMap = (Map<String, String>) context;
    if (contextMap != null) {
      for (Map.Entry<String, String> entry : contextMap.entrySet()) {
        serializedContext.put(
            entry.getKey(), DataConverter.getDefaultInstance().toPayload(entry.getValue()).get());
      }
    }
    return serializedContext;
  }

  @Override
  public Object deserializeContext(Map<String, Payload> context) {
    Map<String, String> contextMap = new HashMap<>();
    for (Map.Entry<String, Payload> entry : context.entrySet()) {
      contextMap.put(
          entry.getKey(),
          DataConverter.getDefaultInstance()
              .fromPayload(entry.getValue(), String.class, String.class));
    }
    return contextMap;
  }

  @Override
  public Object getCurrentContext() {
    Tracer currentTracer = GlobalTracer.get();
    Span currentSpan = currentTracer.scopeManager().activeSpan();
    if (currentSpan != null) {
      HashMapTextMap contextTextMap = new HashMapTextMap();
      currentTracer.inject(currentSpan.context(), Format.Builtin.TEXT_MAP, contextTextMap);
      return contextTextMap.getBackingMap();
    } else {
      return null;
    }
  }

  @Override
  public void setCurrentContext(Object context) {
    Tracer currentTracer = GlobalTracer.get();
    Map<String, String> contextAsMap = (Map<String, String>) context;
    if (contextAsMap != null) {
      HashMapTextMap contextTextMap = new HashMapTextMap(contextAsMap);
      setCurrentOpenTracingSpanContext(
          currentTracer.extract(Format.Builtin.TEXT_MAP, contextTextMap));
    }
  }

  @Override
  public void setUp() {
    Tracer openTracingTracer = GlobalTracer.get();
    Tracer.SpanBuilder builder = openTracingTracer.buildSpan("cadence.workflow");

    if (MDC.getCopyOfContextMap().containsKey(LoggerTag.WORKFLOW_TYPE)) {
      builder.withTag("resource.name", MDC.get(LoggerTag.WORKFLOW_TYPE));
    } else {
      builder.withTag("resource.name", MDC.get(LoggerTag.ACTIVITY_TYPE));
    }

    if (getCurrentOpenTracingSpanContext() != null) {
      builder.asChildOf(getCurrentOpenTracingSpanContext());
    }
    Span span = builder.start();
    openTracingTracer.activateSpan(span);
    currentOpenTracingSpan.set(span);
    Scope scope = openTracingTracer.activateSpan(span);
    currentOpenTracingScope.set(scope);
  }

  @Override
  public void onError(Throwable t) {
    Span span = currentOpenTracingSpan.get();
    if (span != null) {
      Tags.ERROR.set(span, true);
      Map<String, Object> errorData = new HashMap<>();
      errorData.put(Fields.EVENT, "error");
      if (t != null) {
        errorData.put(Fields.ERROR_OBJECT, t);
        errorData.put(Fields.MESSAGE, t.getMessage());
      }
      span.log(errorData);
    }
  }

  @Override
  public void finish() {
    Scope currentScope = currentOpenTracingScope.get();
    Span currentSpan = currentOpenTracingSpan.get();
    if (currentScope != null) {
      currentScope.close();
    }
    if (currentSpan != null) {
      currentSpan.finish();
    }
    currentOpenTracingScope.remove();
    currentOpenTracingSpan.remove();
    currentOpenTracingSpanContext.remove();
  }

  /** Just check for other instances of the same class */
  @Override
  public boolean equals(Object obj) {
    if (obj == null) {
      return false;
    }
    if (this == obj) {
      return true;
    }
    return this.getClass().equals(obj.getClass());
  }

  @Override
  public int hashCode() {
    return this.getClass().hashCode();
  }

  private class HashMapTextMap implements TextMap {
    private final HashMap<String, String> backingMap = new HashMap<>();

    public HashMapTextMap() {
      // Noop
    }

    public HashMapTextMap(Map<String, String> spanData) {
      backingMap.putAll(spanData);
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
      return backingMap.entrySet().iterator();
    }

    @Override
    public void put(String key, String value) {
      backingMap.put(key, value);
    }

    public HashMap<String, String> getBackingMap() {
      return backingMap;
    }
  }
}
