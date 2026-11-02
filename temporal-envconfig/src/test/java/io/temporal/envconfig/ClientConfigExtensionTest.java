package io.temporal.envconfig;

import com.google.common.collect.ImmutableMap;
import io.temporal.common.SimplePlugin;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubsPlugin;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class ClientConfigExtensionTest {
  private static final String EXT_TOML =
      "[profile.default]\n"
          + "address = \"my-address\"\n"
          + "\n"
          + "[profile.default.ext.foo]\n"
          + "some_string = \"some-value\"\n"
          + "some_int = 5\n"
          + "some_bool = true\n"
          + "some_list = [\"a\", \"b\"]\n"
          + "\n"
          + "[profile.default.ext.foo.nested]\n"
          + "some_key = \"nested-value\"\n"
          + "\n"
          + "[profile.default.ext.bar]\n"
          + "unknown_to_everyone = 1.5\n";

  @Test
  public void parseExt() throws IOException {
    for (boolean strict : new boolean[] {false, true}) {
      ClientConfig config =
          ClientConfig.fromToml(
              EXT_TOML.getBytes(),
              ClientConfigFromTomlOptions.newBuilder().setStrictConfigFile(strict).build());
      ClientConfigProfile profile = config.getProfiles().get("default");
      Assert.assertEquals("my-address", profile.getAddress());
      Assert.assertEquals(2, profile.getExt().size());
      Map<String, Object> foo = profile.getExt().get("foo");
      Assert.assertEquals("some-value", foo.get("some_string"));
      Assert.assertEquals(5, ((Number) foo.get("some_int")).intValue());
      Assert.assertEquals(true, foo.get("some_bool"));
      Assert.assertEquals(Arrays.asList("a", "b"), foo.get("some_list"));
      Assert.assertEquals(Collections.singletonMap("some_key", "nested-value"), foo.get("nested"));
      Assert.assertEquals(
          1.5, ((Number) profile.getExt().get("bar").get("unknown_to_everyone")).doubleValue(), 0);
    }

    // Unknown keys outside of ext are still rejected in strict mode
    String toml = EXT_TOML + "\n[profile.default.not_ext]\nfoo = \"bar\"\n";
    ClientConfig.fromToml(toml.getBytes());
    Assert.assertThrows(
        IOException.class,
        () ->
            ClientConfig.fromToml(
                toml.getBytes(),
                ClientConfigFromTomlOptions.newBuilder().setStrictConfigFile(true).build()));
  }

  @Test
  public void parseExtWhenLoadingProfile() throws IOException {
    ClientConfigProfile profile =
        ClientConfigProfile.load(
            LoadClientConfigProfileOptions.newBuilder()
                .setConfigFileData(EXT_TOML.getBytes())
                .setConfigFileStrict(true)
                .setEnvOverrides(Collections.emptyMap())
                .build());
    Assert.assertEquals("some-value", profile.getExt().get("foo").get("some_string"));
    Assert.assertTrue(profile.getPlugins().isEmpty());
  }

  @Test
  public void extIsEmptyWhenUnset() throws IOException {
    Assert.assertTrue(ClientConfigProfile.getDefaultInstance().getExt().isEmpty());
    ClientConfig config = ClientConfig.fromToml("[profile.default]\naddress = \"a\"\n".getBytes());
    Assert.assertTrue(config.getProfiles().get("default").getExt().isEmpty());
  }

  @Test
  public void extIsUnmodifiable() throws IOException {
    ClientConfigProfile profile =
        ClientConfig.fromToml(EXT_TOML.getBytes()).getProfiles().get("default");
    Assert.assertThrows(
        UnsupportedOperationException.class, () -> profile.getExt().put("baz", null));
    Map<String, Object> foo = profile.getExt().get("foo");
    Assert.assertThrows(UnsupportedOperationException.class, () -> foo.put("baz", "qux"));
    Assert.assertThrows(
        UnsupportedOperationException.class, () -> ((List<Object>) foo.get("some_list")).add("c"));
    Assert.assertThrows(
        UnsupportedOperationException.class,
        () -> ((Map<String, Object>) foo.get("nested")).put("baz", "qux"));
  }

  @Test
  public void extensionsCalledInOrder() throws IOException {
    List<String> calls = new ArrayList<>();
    WorkflowServiceStubsPlugin pluginA = SimplePlugin.newBuilder("plugin-a").build();
    WorkflowServiceStubsPlugin pluginC = SimplePlugin.newBuilder("plugin-c").build();
    ClientConfigProfile profile =
        loadWithExtensions(
            new TestExtension("a", req -> recordCall(calls, "a", pluginA)),
            new TestExtension("b", req -> recordCall(calls, "b", null)),
            new TestExtension("c", req -> recordCall(calls, "c", pluginC)));
    Assert.assertEquals(Arrays.asList("a", "b", "c"), calls);
    // Null plugin is skipped
    Assert.assertEquals(Arrays.asList(pluginA, pluginC), profile.getPlugins());

    // Plugins are included in the converted stubs options, and preserved through the builder
    WorkflowServiceStubsOptions stubsOptions = profile.toWorkflowServiceStubsOptions();
    Assert.assertArrayEquals(
        new WorkflowServiceStubsPlugin[] {pluginA, pluginC}, stubsOptions.getPlugins());
    Assert.assertEquals(
        Arrays.asList(pluginA, pluginC),
        profile.toBuilder().setAddress("other").build().getPlugins());
    Assert.assertThrows(
        UnsupportedOperationException.class, () -> profile.getPlugins().add(pluginA));
  }

  @Test
  public void noPluginsWithoutExtensions() throws IOException {
    ClientConfigProfile profile = loadWithExtensions();
    Assert.assertTrue(profile.getPlugins().isEmpty());
    Assert.assertNull(profile.toWorkflowServiceStubsOptions().getPlugins());
  }

  @Test
  public void invalidExtensions() {
    ClientConfigExtension a = new TestExtension("a", req -> null);
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () -> loadWithExtensions(a, new TestExtension("b", req -> null), a));
    Assert.assertEquals("Duplicate client config extension name \"a\"", e.getMessage());

    e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () -> loadWithExtensions(a, new TestExtension("", req -> null)));
    Assert.assertTrue(e.getMessage(), e.getMessage().contains("at index 1"));
    Assert.assertTrue(e.getMessage(), e.getMessage().contains("has empty name"));

    e = Assert.assertThrows(IllegalArgumentException.class, () -> loadWithExtensions(a, null));
    Assert.assertEquals("Client config extension at index 1 is null", e.getMessage());
  }

  @Test
  public void extensionNotCalledIfValidationFails() {
    List<String> calls = new ArrayList<>();
    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            loadWithExtensions(
                new TestExtension("a", req -> recordCall(calls, "a", null)),
                new TestExtension("a", req -> recordCall(calls, "a", null))));
    Assert.assertTrue(calls.isEmpty());
  }

  @Test
  public void extensionErrorsWrapped() {
    IOException ioCause = new IOException("io failure");
    IOException ioException =
        Assert.assertThrows(
            IOException.class,
            () ->
                loadWithExtensions(
                    new TestExtension(
                        "my_ext",
                        req -> {
                          throw ioCause;
                        })));
    Assert.assertEquals(
        "Client config extension \"my_ext\" failed: io failure", ioException.getMessage());
    Assert.assertSame(ioCause, ioException.getCause());

    IllegalStateException runtimeCause = new IllegalStateException("bad config");
    RuntimeException runtimeException =
        Assert.assertThrows(
            RuntimeException.class,
            () ->
                loadWithExtensions(
                    new TestExtension(
                        "my_ext",
                        req -> {
                          throw runtimeCause;
                        })));
    Assert.assertEquals(
        "Client config extension \"my_ext\" failed: bad config", runtimeException.getMessage());
    Assert.assertSame(runtimeCause, runtimeException.getCause());

    // Later extensions are not called after a failure
    List<String> calls = new ArrayList<>();
    Assert.assertThrows(
        RuntimeException.class,
        () ->
            loadWithExtensions(
                new TestExtension(
                    "a",
                    req -> {
                      throw runtimeCause;
                    }),
                new TestExtension("b", req -> recordCall(calls, "b", null))));
    Assert.assertTrue(calls.isEmpty());
  }

  @Test
  public void extensionRequest() throws IOException {
    Map<String, String> env =
        ImmutableMap.of("TEMPORAL_ADDRESS", "env-address", "TEMPORAL_EXT_FOO_KEY", "env-value");
    List<ClientConfigExtensionRequest> requests = new ArrayList<>();
    ClientConfigExtension extension =
        new TestExtension(
            "foo",
            req -> {
              requests.add(req);
              return null;
            });

    // Env overrides are passed through, and the profile has env applied
    ClientConfigProfile.load(
        LoadClientConfigProfileOptions.newBuilder()
            .setConfigFileData(EXT_TOML.getBytes())
            .setEnvOverrides(env)
            .setExtensions(Collections.singletonList(extension))
            .build());
    Assert.assertEquals(env, requests.get(0).getEnv());
    Assert.assertEquals("env-address", requests.get(0).getProfile().getAddress());
    Assert.assertEquals(
        "some-value", requests.get(0).getProfile().getExt().get("foo").get("some_string"));

    // Env is null if disabled
    ClientConfigProfile.load(
        LoadClientConfigProfileOptions.newBuilder()
            .setConfigFileData(EXT_TOML.getBytes())
            .setEnvOverrides(env)
            .setDisableEnv(true)
            .setExtensions(Collections.singletonList(extension))
            .build());
    Assert.assertNull(requests.get(1).getEnv());
    Assert.assertEquals("my-address", requests.get(1).getProfile().getAddress());

    // Process env is used if no overrides
    ClientConfigProfile.load(
        LoadClientConfigProfileOptions.newBuilder()
            .setConfigFileData(EXT_TOML.getBytes())
            .setExtensions(Collections.singletonList(extension))
            .build());
    Assert.assertEquals(System.getenv(), requests.get(2).getEnv());

    // Extensions are called even if file loading is disabled
    ClientConfigProfile.load(
        LoadClientConfigProfileOptions.newBuilder()
            .setDisableFile(true)
            .setEnvOverrides(env)
            .setExtensions(Collections.singletonList(extension))
            .build());
    Assert.assertEquals(env, requests.get(3).getEnv());
    Assert.assertTrue(requests.get(3).getProfile().getExt().isEmpty());
  }

  private static ClientConfigProfile loadWithExtensions(ClientConfigExtension... extensions)
      throws IOException {
    return ClientConfigProfile.load(
        LoadClientConfigProfileOptions.newBuilder()
            .setConfigFileData(EXT_TOML.getBytes())
            .setEnvOverrides(Collections.emptyMap())
            .setExtensions(Arrays.asList(extensions))
            .build());
  }

  private static WorkflowServiceStubsPlugin recordCall(
      List<String> calls, String name, WorkflowServiceStubsPlugin plugin) {
    calls.add(name);
    return plugin;
  }

  private interface PluginFunction {
    WorkflowServiceStubsPlugin apply(ClientConfigExtensionRequest request) throws IOException;
  }

  private static class TestExtension implements ClientConfigExtension {
    private final String name;
    private final PluginFunction function;

    TestExtension(String name, PluginFunction function) {
      this.name = name;
      this.function = function;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public WorkflowServiceStubsPlugin getClientPlugin(ClientConfigExtensionRequest request)
        throws IOException {
      return function.apply(request);
    }
  }
}
