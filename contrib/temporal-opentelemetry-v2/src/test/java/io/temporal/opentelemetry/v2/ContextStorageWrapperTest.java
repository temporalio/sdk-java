package io.temporal.opentelemetry.v2;

import static org.junit.Assert.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;
import org.junit.Test;

public class ContextStorageWrapperTest {
  @Test
  public void pluginAcceptsAWrapperRegisteredAfterReplaySafeOpenTelemetry() {
    try (ReplaySafeOpenTelemetry openTelemetry = ReplaySafeOpenTelemetry.newBuilder().build()) {
      ContextStorage.addWrapper(OuterStorage::new);
      GlobalOpenTelemetry.set(openTelemetry);
      OpenTelemetryPlugin.newBuilder().build();
    } finally {
      GlobalOpenTelemetry.resetForTest();
    }
    assertTrue(ContextStorage.get() instanceof OuterStorage);
  }

  private static final class OuterStorage implements ContextStorage {
    private final ContextStorage delegate;

    OuterStorage(ContextStorage delegate) {
      this.delegate = delegate;
    }

    @Override
    public Scope attach(Context context) {
      return delegate.attach(context);
    }

    @Override
    public Context current() {
      return delegate.current();
    }

    @Override
    public Context root() {
      return delegate.root();
    }
  }
}
