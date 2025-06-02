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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Classpath helpers for a class loader to get all classes. */
class ClassPath implements AutoCloseable {
  static boolean isStandardLibraryClass(String name) {
    return name.startsWith("java/")
        || name.startsWith("javax/")
        || name.startsWith("jdk/")
        || name.startsWith("com/sun/");
  }

  final URLClassLoader classLoader;
  // Non-standard-library classes only here
  final List<String> classes = new ArrayList<>();

  ClassPath(String... classPaths) throws IOException {
    List<URL> urls = new ArrayList<>();
    for (String classPath : classPaths) {
      // If there is an `@` sign starting the classPath, instead read from a file
      if (classPath.startsWith("@")) {
        classPath =
            new String(
                    Files.readAllBytes(Paths.get(classPath.substring(1))), StandardCharsets.UTF_8)
                .trim();
      }
      // Split and handle each entry
      for (String entry : classPath.split(File.pathSeparator)) {
        File file = new File(entry);
        // Like javac and others, we just ignore non-existing entries
        if (file.exists()) {
          if (file.isDirectory()) {
            urls.add(file.toURI().toURL());
            findClassesInDir("", file, classes);
          } else if (entry.endsWith(".jar")) {
            urls.add(file.getAbsoluteFile().toURI().toURL());
            findClassesInJar(file, classes);
          }
        }
      }
    }
    classLoader = new URLClassLoader(urls.toArray(new URL[0]));
    // Sort the classes to loaded in a deterministic order
    classes.sort(String::compareTo);
  }

  private static void findClassesInDir(String path, File dir, List<String> classes) {
    File[] files = dir.listFiles();
    if (files == null) {
      return;
    }
    for (File file : files) {
      if (file.isDirectory()) {
        findClassesInDir(path + file.getName() + "/", file, classes);
      } else if (file.getName().endsWith(".class")) {
        addClass(path + file.getName(), classes);
      }
    }
  }

  private static void findClassesInJar(File jar, List<String> classes) throws IOException {
    try (JarFile jarFile = new JarFile(jar)) {
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.getName().endsWith(".class")) {
          addClass(entry.getName(), classes);
        }
      }
    }
  }

  private static void addClass(String fullPath, List<String> classes) {
    // Trim off trailing .class
    String className = fullPath.substring(0, fullPath.length() - 6);
    // Only if not built in
    if (!isStandardLibraryClass(className)) {
      classes.add(className);
    }
  }

  @Override
  public void close() throws IOException {
    classLoader.close();
  }
}
