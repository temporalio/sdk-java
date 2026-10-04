package io.temporal.common.converter;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Verifies that the Java 8 stub of {@link Jackson3JsonPayloadConverter} declares the same public
 * API as the Java 17 class, as required for versioned entries of a multi-release jar. Tools that
 * compile against the base entry otherwise cannot resolve members that exist only in the Java 17
 * class.
 */
public class Jackson3JsonPayloadConverterApiTest {

  private static final String CLASS_FILE =
      Jackson3JsonPayloadConverter.class.getName().replace('.', '/') + ".class";

  @Test
  public void testStubDeclaresTheSamePublicApi() throws Exception {
    byte[] stub = readStub();
    Class<?> stubClass =
        new ClassLoader(getClass().getClassLoader()) {
          Class<?> define() {
            return defineClass(null, stub, 0, stub.length);
          }
        }.define();

    assertEquals(publicApi(Jackson3JsonPayloadConverter.class), publicApi(stubClass));
  }

  private static byte[] readStub() throws IOException {
    String dirs = System.getProperty("temporal.sdk.mainClassesDirs");
    for (String dir : dirs.split(File.pathSeparator, -1)) {
      File classFile = new File(dir, CLASS_FILE);
      if (classFile.isFile()) {
        return Files.readAllBytes(classFile.toPath());
      }
    }
    throw new AssertionError("stub not found in " + dirs);
  }

  private static Set<String> publicApi(Class<?> clazz) {
    Set<String> api = new TreeSet<>();
    for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
      if (Modifier.isPublic(constructor.getModifiers())) {
        api.add(constructor.toGenericString());
      }
    }
    for (Method method : clazz.getDeclaredMethods()) {
      if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
        api.add(method.toGenericString());
      }
    }
    return api;
  }
}
