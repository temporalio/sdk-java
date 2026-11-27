package io.temporal.opentracing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.opentracing.Tracer;
import io.opentracing.noop.NoopTracerFactory;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class OpenTracingOptionsTest {

  @Test
  public void explicitTracerDoesNotLoadGlobalTracer() throws Exception {
    Tracer customTracer = NoopTracerFactory.create();
    try (GlobalTracerBlockingClassLoader classLoader = new GlobalTracerBlockingClassLoader()) {
      Class<?> optionsClass = Class.forName(OpenTracingOptions.class.getName(), true, classLoader);
      Object builder = optionsClass.getMethod("newBuilder").invoke(null);
      builder.getClass().getMethod("setTracer", Tracer.class).invoke(builder, customTracer);
      Object options = builder.getClass().getMethod("build").invoke(builder);
      Object configuredTracer = optionsClass.getMethod("getTracer").invoke(options);

      assertSame(customTracer, configuredTracer);
      assertFalse(classLoader.wasGlobalTracerRequested());
    }
  }

  @Test
  public void buildWithoutExplicitTracerLoadsGlobalTracer() throws Exception {
    try (GlobalTracerBlockingClassLoader classLoader = new GlobalTracerBlockingClassLoader()) {
      Class<?> optionsClass = Class.forName(OpenTracingOptions.class.getName(), true, classLoader);
      Object builder = optionsClass.getMethod("newBuilder").invoke(null);

      assertFalse(classLoader.wasGlobalTracerRequested());
      InvocationTargetException thrown =
          assertThrows(
              InvocationTargetException.class,
              () -> builder.getClass().getMethod("build").invoke(builder));
      assertTrue(thrown.getCause() instanceof NoClassDefFoundError);
      assertTrue(classLoader.wasGlobalTracerRequested());
    }
  }

  @Test
  public void getDefaultInstanceLoadsGlobalTracerLazily() throws Exception {
    try (GlobalTracerBlockingClassLoader classLoader = new GlobalTracerBlockingClassLoader()) {
      Class<?> optionsClass = Class.forName(OpenTracingOptions.class.getName(), true, classLoader);

      assertFalse(classLoader.wasGlobalTracerRequested());
      InvocationTargetException thrown =
          assertThrows(
              InvocationTargetException.class,
              () -> optionsClass.getMethod("getDefaultInstance").invoke(null));
      assertTrue(thrown.getCause() instanceof NoClassDefFoundError);
      assertTrue(classLoader.wasGlobalTracerRequested());
    }
  }

  @Test
  public void getDefaultInstanceReturnsSingleton() {
    OpenTracingOptions first = OpenTracingOptions.getDefaultInstance();
    OpenTracingOptions second = OpenTracingOptions.getDefaultInstance();
    assertNotNull(first);
    assertSame(first, second);
    assertNotNull(first.getTracer());
  }

  private static final class GlobalTracerBlockingClassLoader extends URLClassLoader {
    private static final String GLOBAL_TRACER_CLASS = "io.opentracing.util.GlobalTracer";
    private static final String OPEN_TRACING_OPTIONS_CLASS = OpenTracingOptions.class.getName();

    private final AtomicBoolean globalTracerRequested = new AtomicBoolean(false);

    GlobalTracerBlockingClassLoader() {
      super(
          new URL[] {OpenTracingOptions.class.getProtectionDomain().getCodeSource().getLocation()},
          OpenTracingOptions.class.getClassLoader());
    }

    boolean wasGlobalTracerRequested() {
      return globalTracerRequested.get();
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        if (GLOBAL_TRACER_CLASS.equals(name)) {
          globalTracerRequested.set(true);
          throw new ClassNotFoundException("Blocked loading " + name);
        }
        if (OPEN_TRACING_OPTIONS_CLASS.equals(name)
            || name.startsWith(OPEN_TRACING_OPTIONS_CLASS + "$")) {
          Class<?> loaded = findLoadedClass(name);
          if (loaded == null) {
            loaded = findClass(name);
          }
          if (resolve) {
            resolveClass(loaded);
          }
          return loaded;
        }
        return super.loadClass(name, resolve);
      }
    }
  }
}
