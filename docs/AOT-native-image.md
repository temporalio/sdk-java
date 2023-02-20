# Ahead Of Time (AOT) compilation

Ahead Of Time compilation build tools such as those provided by [GraalVM's `native-image`](https://www.graalvm.org/reference-manual/native-image/) require some configuration when using Temporal JavaSDK.
Temporal JavaSDK uses Java proxies to provide Workflow and Activity stubs and the target classes of these proxies are application-specific. Proxy classes need to be generated at a build time by the current AOT tools.
Temporal JavaSDK dependencies like gRPC and Protobuf use reflection and some of its target classes are sometimes not possible to determine in advance during the static analysis.

## [native-image](https://www.graalvm.org/reference-manual/native-image/)

Temporal JavaSDK and Test Server support and can be used with GraalVM `native-image`.

Temporal JavaSDK team does its best effort to maintain as complete `native-image` descriptors as technically possible for the following modules:
`temporal-sdk`, `temporal-testing`, `temporal-serviceclient`, `temporal-test-server`.
These modules are distributed with the embedded `native-image` descriptors.
While these descriptors provide enough information for `native-image` to build and run a fully functional Temporal Test Server,
any user building an application with Temporal JavaSDK will require an additional configuration for the reasons mentioned above: user interfaces are used by the JavaSDK for proxy creation, and they need to know in advance for `native-image`.

`native-image` build can be configured with respect to JNI, java proxying, reflection, etc
by providing [JSON configuration descriptors](https://www.graalvm.org/22.3/reference-manual/native-image/metadata/#specifying-metadata-with-json)
which help `native-image` during building of the native execution file.
This can be done manually, but it's labor-intensive and requires good understanding of `native-image` build [process](https://www.graalvm.org/22.3/reference-manual/native-image/basics/) and [configuration](https://www.graalvm.org/22.3/reference-manual/native-image/overview/Build-Overview/).

Instead we recommend users to run their JVM application along with
[the native-image Tracing agent](https://www.graalvm.org/22.3/reference-manual/native-image/metadata/AutomaticMetadataCollection/).
For example, the agent can be run with the full set of integration tests of the app to cover the largest variety of code paths.
This agent will automatically generate additional descriptor files that users should [place and retain with their project's source code](https://www.graalvm.org/22.3/reference-manual/native-image/overview/BuildConfiguration/#embed-a-configuration-file) under `META-INF/native-image`.