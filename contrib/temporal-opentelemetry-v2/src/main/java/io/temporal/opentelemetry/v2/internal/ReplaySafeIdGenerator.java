package io.temporal.opentelemetry.v2.internal;

import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.util.Random;
import javax.annotation.Nullable;

/**
 * Generates span and trace IDs that are replay safe.
 *
 * <p>Mirrors OpenTelemetry's <a href=
 * "https://github.com/open-telemetry/opentelemetry-java/blob/v1.25.0/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/RandomIdGenerator.java">RandomIdGenerator</a>,
 * replacing its platform random source with a workflow random stream.
 *
 * <p>Note: {@link Random} has 48 bits of state, so IDs can collide across workflows once an
 * installation has generated on the order of 2^24 trace IDs. The JDK has no deterministic random
 * source with a wider seed that can also be reseeded. A random source with more state may be
 * considered in the future.
 */
public final class ReplaySafeIdGenerator implements IdGenerator {
  static final ContextKey<Boolean> INTERCEPTOR_SPAN = ContextKey.named("temporal-interceptor-span");
  private static final String INTERCEPTOR_STREAM = "io.temporal.opentelemetry.v2/interceptor";
  private static final String APPLICATION_STREAM = "io.temporal.opentelemetry.v2/application";

  private static final long INVALID_ID = 0;

  @Override
  public String generateSpanId() {
    Random stream = getStream();
    if (stream == null) {
      return IdGenerator.random().generateSpanId();
    }

    long id;
    do {
      id = stream.nextLong();
    } while (id == INVALID_ID);
    return SpanId.fromLong(id);
  }

  @Override
  public String generateTraceId() {
    Random stream = getStream();
    if (stream == null) {
      return IdGenerator.random().generateTraceId();
    }

    long idHi = stream.nextLong();
    long idLo;
    do {
      idLo = stream.nextLong();
    } while (idLo == INVALID_ID);
    return TraceId.fromLongs(idHi, idLo);
  }

  /**
   * Interceptor spans and application spans draw from separate streams so their IDs never collide.
   * Null means the regular PRNG can be used.
   */
  @Nullable
  private static Random getStream() {
    if (!WorkflowUnsafe.isWorkflowThread() || !WorkflowUnsafe.isSubjectToReplay()) {
      return null;
    }

    Context context = Context.current();
    if (context.get(INTERCEPTOR_SPAN) != null) {
      return Workflow.getRandomStream(INTERCEPTOR_STREAM);
    }

    String tracerName = context.get(ReplaySafeTracer.TRACER_NAME);
    if (tracerName == null) {
      throw new IllegalStateException(
          "Workflow span started without a replay safe tracer. Ensure tracers used in workflows "
              + "are obtained from ReplaySafeOpenTelemetry");
    }
    return Workflow.getRandomStream(APPLICATION_STREAM + "/" + tracerName);
  }
}
