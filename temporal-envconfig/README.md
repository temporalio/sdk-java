# Temporal Environment Config module

Loads [client environment configuration](https://docs.temporal.io/develop/environment-configuration)
from a TOML file and `TEMPORAL_*` environment variables, and converts it into client options:

```java
ClientConfigProfile profile = ClientConfigProfile.load();
WorkflowServiceStubs service =
    WorkflowServiceStubs.newServiceStubs(profile.toWorkflowServiceStubsOptions());
WorkflowClient client = WorkflowClient.newInstance(service, profile.toWorkflowClientOptions());
```

By default, configuration is loaded from `TEMPORAL_CONFIG_FILE` or the platform-specific user
configuration directory, then overridden by supported `TEMPORAL_` environment variables. See
`LoadClientConfigProfileOptions` for profiles, file sources, and other loading options.

## Extensions

Separately packaged modules can add their own configuration and client behavior by implementing
`ClientConfigExtension` (experimental). Each extension owns a TOML table under `ext` in each
profile:

```toml
[profile.default.ext.my_extension]
some_field = "value"
```

These tables are parsed as raw, untyped data into `ClientConfigProfile.getExt()`, and are never
rejected by strict mode, whether or not a matching extension is loaded. Each extension reads and
validates its own table.

Pass extensions when loading a profile. Each is called in order with the loaded profile and
environment, and any `WorkflowServiceStubsPlugin` it returns is added to the profile's plugins,
which `toWorkflowServiceStubsOptions()` includes:

```java
ClientConfigProfile profile =
    ClientConfigProfile.load(
        LoadClientConfigProfileOptions.newBuilder()
            .setExtensions(Collections.singletonList(new MyExtension()))
            .build());
WorkflowServiceStubsOptions stubsOptions = profile.toWorkflowServiceStubsOptions();
```

Returned plugins that also implement `WorkflowClientPlugin` (such as subclasses of `SimplePlugin`)
are propagated from the service stubs to the workflow client, and from there to workers. If you set
your own service stubs plugins, add them to the converted options rather than replacing them, so
the extension plugins are kept.

Extensions are recommended to use a `TEMPORAL_EXT_<NAME>_` prefix for their environment
variables. See `ExampleClientConfigExtensionTest` in this module's tests for a complete extension.
