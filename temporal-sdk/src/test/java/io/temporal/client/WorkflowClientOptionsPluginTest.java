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

package io.temporal.client;

import static org.junit.Assert.*;

import io.temporal.common.SimplePlugin;
import org.junit.Test;

public class WorkflowClientOptionsPluginTest {

  @Test
  public void testDefaultPluginsEmpty() {
    WorkflowClientOptions options = WorkflowClientOptions.newBuilder().build();
    assertEquals("Default plugins should be empty", 0, options.getPlugins().length);
  }

  @Test
  public void testSetPlugins() {
    SimplePlugin plugin1 = new TestPlugin("plugin1");
    SimplePlugin plugin2 = new TestPlugin("plugin2");

    WorkflowClientOptions options =
        WorkflowClientOptions.newBuilder().setPlugins(plugin1, plugin2).build();

    WorkflowClientPlugin[] plugins = options.getPlugins();
    assertEquals(2, plugins.length);
    assertEquals("plugin1", plugins[0].getName());
    assertEquals("plugin2", plugins[1].getName());
  }

  @Test
  public void testToBuilder() {
    SimplePlugin plugin = new TestPlugin("plugin");

    WorkflowClientOptions original = WorkflowClientOptions.newBuilder().setPlugins(plugin).build();

    WorkflowClientOptions copy = original.toBuilder().build();

    assertEquals(1, copy.getPlugins().length);
    assertEquals("plugin", copy.getPlugins()[0].getName());
  }

  @Test
  public void testValidateAndBuildWithDefaults() {
    SimplePlugin plugin = new TestPlugin("plugin");

    WorkflowClientOptions options =
        WorkflowClientOptions.newBuilder().setPlugins(plugin).validateAndBuildWithDefaults();

    assertEquals(1, options.getPlugins().length);
    assertEquals("plugin", options.getPlugins()[0].getName());
  }

  private static class TestPlugin extends SimplePlugin {
    TestPlugin(String name) {
      super(name);
    }
  }
}
