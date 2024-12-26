/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
