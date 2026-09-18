package io.temporal.opentelemetry.v2.internal;

import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.ContextStorageProvider;

/** Provides the Temporal-aware OpenTelemetry context storage before OpenTelemetry initializes. */
public final class TemporalContextStorageProvider implements ContextStorageProvider {
  @Override
  public ContextStorage get() {
    return new TemporalContextStorage(ContextStorage.defaultStorage());
  }
}
