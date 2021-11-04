# Temporal Java SDK  [![Build status](https://badge.buildkite.com/663f6d1be81be6700c28c242b35905f20b68c4fda7b2c7c4e3.svg)](https://buildkite.com/temporal/java-sdk-public)

[Temporal](https://github.com/temporalio/temporal) is a Workflow as Code platform used to build and operate
resilient applications using developer friendly primitives, instead of constantly fighting your infrastructure.

`temporal-java-sdk` is the framework for authoring workflows and activities in Java.

If you are authoring in Go, see [Temporal Go SDK](https://github.com/temporalio/sdk-go).

## Samples

For samples, see [Samples for the Temporal Java SDK](https://github.com/temporalio/samples-java).

## Run Temporal Server

Follow the [Quick install guide](https://docs.temporal.io/docs/server/quick-install) to run the Temporal Server locally.
Additional information is available in the [Temporal Server docker-compose](https://github.com/temporalio/docker-compose) repo.

## Get CLI

[CLI is available as an executable or as a docker image](https://github.com/temporalio/temporal/blob/master/tools/cli/README.md)

## Build a configuration

[Find the latest release](https://search.maven.org/artifact/io.temporal/temporal-sdk) of the Temporal Java SDK at maven central.

Add *temporal-sdk* as a dependency to your *pom.xml*:

    <dependency>
      <groupId>io.temporal</groupId>
      <artifactId>temporal-sdk</artifactId>
      <version>N.N.N</version>
    </dependency>

or to *build.gradle*:

    compile group: 'io.temporal', name: 'temporal-sdk', version: 'N.N.N'

## Documentation

The documentation on how to use the Temporal Java client is [here](https://docs.temporal.io/docs/java/introduction).

Javadocs for the client API are located [here](https://www.javadoc.io/doc/io.temporal/temporal-sdk).

### macOS Users
Due to issues with default hostname resolution
(see [this StackOverflow question](https://stackoverflow.com/questions/33289695/inetaddress-getlocalhost-slow-to-run-30-seconds) for more details),
macOS Users may see gRPC `DEADLINE_EXCEEDED` errors and other slowdowns when running the SDK.

To solve the problem add the following entries to your `/etc/hosts` file (where my-macbook is your hostname):

```conf
127.0.0.1   my-macbook
::1         my-macbook
```

## Contributing
We'd love your help in making the Temporal Java SDK great. Please review our [contribution guidelines](CONTRIBUTING.md).

## License
Apache License, please see [LICENSE](LICENSE) for details.
