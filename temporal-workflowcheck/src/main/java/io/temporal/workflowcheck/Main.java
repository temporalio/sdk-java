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

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/** Entrypoint for CLI. */
public class Main {
  public static void main(String[] args) throws IOException {
    if (args.length == 0 || "--help".equals(args[0])) {
      System.err.println(
          "Analyze Temporal workflows for common mistakes.\n"
              + "\n"
              + "Usage:\n"
              + "  workflowcheck [command]\n"
              + "\n"
              + "Commands:\n"
              + "  check - Check all workflow code on the classpath for invalid calls\n"
              + "  prebuild-config - Pre-build a config for certain packages to keep from scanning each time (TODO)");
      return;
    }
    switch (args[0]) {
      case "check":
        System.exit(check(Arrays.copyOfRange(args, 1, args.length)));
      case "prebuild-config":
        System.exit(prebuildConfig(Arrays.copyOfRange(args, 1, args.length)));
      default:
        System.err.println("Unrecognized command '" + args[0] + "'");
        System.exit(1);
    }
  }

  private static int check(String[] args) throws IOException {
    if (args.length == 1 && "--help".equals(args[0])) {
      System.err.println(
          "Analyze Temporal workflows for common mistakes.\n"
              + "\n"
              + "Usage:\n"
              + "  workflowcheck check <classpath...> [--config <config-file>] [--no-default-config] [--show-valid]");
      return 0;
    }
    // Args list that removes options as encountered
    List<String> argsList = new ArrayList<>(Arrays.asList(args));

    // Load config
    List<Properties> configProps = new ArrayList<>();
    if (!argsList.remove("--no-default-config")) {
      configProps.add(Config.defaultProperties());
    }
    while (true) {
      int configIndex = argsList.indexOf("--config");
      if (configIndex == -1) {
        break;
      } else if (configIndex == argsList.size() - 1) {
        System.err.println("Missing --config value");
        return 1;
      }
      argsList.remove(configIndex);
      Properties props = new Properties();
      try (FileInputStream is = new FileInputStream(argsList.remove(configIndex))) {
        props.load(is);
      }
      configProps.add(props);
    }

    // Whether we should also show valid
    boolean showValid = argsList.remove("--show-valid");

    // Ensure that we have at least one classpath arg
    if (argsList.isEmpty()) {
      System.err.println("At least one classpath argument required");
      return 1;
    }
    // While it can rarely be possible for the first file in a class path string
    // to start with a dash, we're going to assume it's an invalid argument and
    // users can qualify if needed.
    Optional<String> invalidArg = argsList.stream().filter(s -> s.startsWith("-")).findFirst();
    if (invalidArg.isPresent()) {
      System.err.println("Unrecognized argument: " + invalidArg);
    }

    System.err.println("Analyzing classpath for classes with workflow methods...");
    Config config = Config.fromProperties(configProps.toArray(new Properties[0]));
    List<ClassInfo> infos =
        new WorkflowCheck(config).findWorkflowClasses(argsList.toArray(new String[0]));
    System.out.println("Found " + infos.size() + " class(es) with workflow methods");
    if (infos.isEmpty()) {
      return 0;
    }

    // Print workflow methods impls
    boolean anyInvalidImpls = false;
    for (ClassInfo info : infos) {
      List<Map.Entry<String, List<ClassInfo.MethodInfo>>> methodEntries =
          info.methods.entrySet().stream()
              .sorted(Map.Entry.comparingByKey())
              .collect(Collectors.toList());
      for (Map.Entry<String, List<ClassInfo.MethodInfo>> methods : methodEntries) {
        for (ClassInfo.MethodInfo method : methods.getValue()) {
          // Only impls
          if (method.workflowImpl == null) {
            continue;
          }
          if (showValid || method.isInvalid()) {
            System.out.println(Printer.methodText(info, methods.getKey(), method));
          }
          if (method.isInvalid()) {
            anyInvalidImpls = true;
          }
        }
      }
    }
    return anyInvalidImpls ? 1 : 0;
  }

  private static int prebuildConfig(String[] args) {
    System.err.println("TODO");
    return 1;
  }

  private Main() {}
}
