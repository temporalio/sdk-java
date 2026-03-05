package io.temporal.workflowcheck;

import static org.junit.Assert.*;

import java.io.File;
import org.junit.Test;

public class ClassPathTest {
  @Test
  public void testClassPath() throws Exception {
    // We need to test a file-based classpath and a JAR based one (including
    // built-in classes) and confirm all loaded properly. We have confirmed
    // with Gradle tests that we have the proper pieces, but we assert again.
    String testClassDirEntry = null;
    String asmJarEntry = null;
    for (String maybeEntry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      String url = new File(maybeEntry).toURI().toURL().toString();
      if (url.endsWith("classes/java/test/")) {
        assertNull(testClassDirEntry);
        testClassDirEntry = maybeEntry;
      } else {
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        if (fileName.startsWith("asm-") && fileName.endsWith(".jar")) {
          assertNull(asmJarEntry);
          asmJarEntry = maybeEntry;
        }
      }
    }
    assertNotNull(testClassDirEntry);
    assertNotNull(asmJarEntry);

    // Now use these to load all classes and confirm it has the proper ones
    // present
    try (ClassPath classPath =
        new ClassPath(testClassDirEntry + File.pathSeparator + asmJarEntry)) {
      assertTrue(
          classPath.classes.contains("io/temporal/workflowcheck/testdata/BadCalls$BadCallsImpl"));
      assertTrue(classPath.classes.contains("org/objectweb/asm/ClassReader"));
    }
  }
}
