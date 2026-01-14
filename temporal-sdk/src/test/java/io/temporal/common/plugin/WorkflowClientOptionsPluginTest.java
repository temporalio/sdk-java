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

package io.temporal.common.plugin;

import static org.junit.Assert.*;

import io.temporal.client.WorkflowClientOptions;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class WorkflowClientOptionsPluginTest {

  @Test
  public void testDefaultPluginsEmpty() {
    WorkflowClientOptions options = WorkflowClientOptions.newBuilder().build();
    assertTrue("Default plugins should be empty", options.getPlugins().isEmpty());
  }

  @Test
  public void testSetPlugins() {
    PluginBase plugin1 = new TestPlugin("plugin1");
    PluginBase plugin2 = new TestPlugin("plugin2");

    WorkflowClientOptions options =
        WorkflowClientOptions.newBuilder().setPlugins(Arrays.asList(plugin1, plugin2)).build();

    List<?> plugins = options.getPlugins();
    assertEquals(2, plugins.size());
    assertEquals("plugin1", ((ClientPlugin) plugins.get(0)).getName());
    assertEquals("plugin2", ((ClientPlugin) plugins.get(1)).getName());
  }

  @Test
  public void testAddPlugin() {
    PluginBase plugin1 = new TestPlugin("plugin1");
    PluginBase plugin2 = new TestPlugin("plugin2");

    WorkflowClientOptions options =
        WorkflowClientOptions.newBuilder().addPlugin(plugin1).addPlugin(plugin2).build();

    List<?> plugins = options.getPlugins();
    assertEquals(2, plugins.size());
    assertEquals("plugin1", ((ClientPlugin) plugins.get(0)).getName());
    assertEquals("plugin2", ((ClientPlugin) plugins.get(1)).getName());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testPluginsAreImmutable() {
    PluginBase plugin = new TestPlugin("plugin");

    WorkflowClientOptions options = WorkflowClientOptions.newBuilder().addPlugin(plugin).build();

    List<Object> plugins = (List<Object>) options.getPlugins();
    try {
      plugins.add(new TestPlugin("another"));
      fail("Should not be able to modify plugins list");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  public void testSetPluginsNull() {
    WorkflowClientOptions options = WorkflowClientOptions.newBuilder().setPlugins(null).build();
    assertTrue("Null plugins should result in empty list", options.getPlugins().isEmpty());
  }

  @Test(expected = NullPointerException.class)
  public void testAddPluginNull() {
    WorkflowClientOptions.newBuilder().addPlugin(null);
  }

  @Test
  public void testToBuilder() {
    PluginBase plugin = new TestPlugin("plugin");

    WorkflowClientOptions original = WorkflowClientOptions.newBuilder().addPlugin(plugin).build();

    WorkflowClientOptions copy = original.toBuilder().build();

    assertEquals(1, copy.getPlugins().size());
    assertEquals("plugin", ((ClientPlugin) copy.getPlugins().get(0)).getName());
  }

  @Test
  public void testValidateAndBuildWithDefaults() {
    PluginBase plugin = new TestPlugin("plugin");

    WorkflowClientOptions options =
        WorkflowClientOptions.newBuilder().addPlugin(plugin).validateAndBuildWithDefaults();

    assertEquals(1, options.getPlugins().size());
    assertEquals("plugin", ((ClientPlugin) options.getPlugins().get(0)).getName());
  }

  @Test
  public void testEqualsWithPlugins() {
    PluginBase plugin = new TestPlugin("plugin");

    WorkflowClientOptions options1 = WorkflowClientOptions.newBuilder().addPlugin(plugin).build();

    WorkflowClientOptions options2 = WorkflowClientOptions.newBuilder().addPlugin(plugin).build();

    assertEquals(options1, options2);
    assertEquals(options1.hashCode(), options2.hashCode());
  }

  @Test
  public void testToStringWithPlugins() {
    PluginBase plugin = new TestPlugin("my-plugin");

    WorkflowClientOptions options = WorkflowClientOptions.newBuilder().addPlugin(plugin).build();

    String str = options.toString();
    assertTrue("toString should contain plugins", str.contains("plugins"));
  }

  private static class TestPlugin extends PluginBase {
    TestPlugin(String name) {
      super(name);
    }
  }
}
