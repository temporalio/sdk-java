package io.temporal.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.temporal.testing.CloudTestExclusion.NeedsCloudAdaptation;
import io.temporal.testing.CloudTestExclusion.RequiresCloudProvisioning;
import io.temporal.testing.CloudTestExclusion.RequiresLocalServer;
import java.io.File;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.experimental.categories.Categories.CategoryFilter;
import org.junit.experimental.categories.Category;
import org.junit.runner.Description;

public class CloudTestExclusionTest {
  private static final Set<Class<?>> REASONS =
      new HashSet<>(
          Arrays.asList(
              RequiresLocalServer.class,
              RequiresCloudProvisioning.class,
              NeedsCloudAdaptation.class));

  @Test
  public void cloudTestExclusionContract() throws Exception {
    assertUmbrellaCategoryExcludesEveryReason();
    assertEveryCloudExclusionHasOneReasonAndNote();
  }

  private static void assertUmbrellaCategoryExcludesEveryReason() throws Exception {
    CategoryFilter filter = CategoryFilter.exclude(CloudTestExclusion.class);

    assertFalse(
        filter.shouldRun(
            Description.createSuiteDescription(
                ClassExcludedFixture.class.getName(),
                ClassExcludedFixture.class.getAnnotations())));
    assertFalse(filter.shouldRun(methodDescription("requiresCloudProvisioning")));
    assertFalse(filter.shouldRun(methodDescription("needsCloudAdaptation")));
    assertTrue(filter.shouldRun(methodDescription("cloudEligible")));
  }

  private static void assertEveryCloudExclusionHasOneReasonAndNote() throws Exception {
    for (Class<?> testClass : testClasses()) {
      validateElement(testClass.getName(), testClass);
      for (Method method : testClass.getDeclaredMethods()) {
        validateElement(testClass.getName() + "#" + method.getName(), method);
      }
    }
  }

  private static List<Class<?>> testClasses() throws Exception {
    Path classesRoot =
        Paths.get(
            CloudTestExclusionTest.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    Path packageRoot = classesRoot.resolve("io/temporal");
    try (Stream<Path> paths = Files.walk(packageRoot)) {
      return paths
          .filter(path -> path.toString().endsWith(".class"))
          .map(classesRoot::relativize)
          .map(Path::toString)
          .filter(classFile -> !classFile.contains("CloudTestExclusionTest$"))
          .sorted()
          .map(CloudTestExclusionTest::loadClass)
          .collect(Collectors.toList());
    }
  }

  private static Class<?> loadClass(String classFile) {
    String className =
        classFile
            .substring(0, classFile.length() - ".class".length())
            .replace(File.separatorChar, '.');
    try {
      return Class.forName(className, false, CloudTestExclusionTest.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("Unable to inspect test class " + className + ".", e);
    }
  }

  private static void validateElement(String name, AnnotatedElement element) {
    Category category = element.getAnnotation(Category.class);
    CloudTestExclusionNote note = element.getAnnotation(CloudTestExclusionNote.class);
    List<Class<?>> reasons =
        category == null
            ? java.util.Collections.emptyList()
            : Arrays.stream(category.value())
                .filter(CloudTestExclusion.class::isAssignableFrom)
                .collect(Collectors.toList());
    if (reasons.isEmpty()) {
      assertNull(name + " has a Cloud exclusion note without a reason category.", note);
      return;
    }

    assertEquals(name + " must have exactly one Cloud exclusion reason.", 1, reasons.size());
    assertTrue(name + " uses an unknown Cloud exclusion reason.", REASONS.contains(reasons.get(0)));
    assertNotNull(name + " must explain its Cloud exclusion.", note);
    assertFalse(
        name + " must have a nonblank Cloud exclusion note.", note.value().trim().isEmpty());
  }

  private static Description methodDescription(String name) throws Exception {
    Method method = MethodExcludedFixture.class.getDeclaredMethod(name);
    return Description.createTestDescription(
        MethodExcludedFixture.class, name, method.getAnnotations());
  }

  @CloudTestExclusionNote("Requires a local server for this filtering fixture.")
  @Category(RequiresLocalServer.class)
  private static class ClassExcludedFixture {}

  private static class MethodExcludedFixture {
    public void cloudEligible() {}

    @CloudTestExclusionNote("Requires Cloud provisioning for this filtering fixture.")
    @Category(RequiresCloudProvisioning.class)
    public void requiresCloudProvisioning() {}

    @CloudTestExclusionNote("Needs Cloud adaptation for this filtering fixture.")
    @Category(NeedsCloudAdaptation.class)
    public void needsCloudAdaptation() {}
  }
}
