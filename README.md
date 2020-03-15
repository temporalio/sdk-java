# Temporal Java SDK  [![Build Status]()](https://buildkite.com/uberopensource/cadence-java-client) [![Javadocs]()]()


[Temporal](https://github.com/temporalio/temporal) is a Workflow as Code platform used to build and operate 
resilient applications using developer friendly primitives, instead of constantly fighting your infrastructure.

`temporal-java-sdk` is the framework for authoring workflows and activities in Java.

If you are authoring in Go, see [Temporal Go SDK](https://github.com/temporalio/temoral-go-sdk).

## Samples

For samples, see [Samples for the Temporal Java SDK](https://github.com/temporalio/temporal-java-samples).

## Run Temporal Server

Run Temporal Server using Docker Compose:

    curl -O https://raw.githubusercontent.com/temporalio/temporal/master/docker/docker-compose.yml
    docker-compose up

If this does not work, see instructions for running the Temporal Server at https://github.com/temporalio/temporal/blob/master/README.md.

## Get CLI

[CLI is avaialable as an executable or as a docker image](https://github.com/temporalio/temporal/blob/master/tools/cli/README.md)

## Build a configuration

Add *temporal-sdk* as a dependency to your *pom.xml*:

    <dependency>
      <groupId>io.temporal</groupId>
      <artifactId>temporal-sdk</artifactId>
      <version>1.0.0</version>
    </dependency>
    
or to *build.gradle*:

    compile group: 'io.temporal', name: 'temporal-sdk', version: '1.0.0'

## Documentation

The documentation on how to use the Temporal Java client is [here](https://docs.temporal.io/06_javaclient/).

Javadocs for the client API are located [here](https://www.javadoc.io/doc/io.temporal/temporal-sdk).

## Contributing
We'd love your help in making the Temporal Java SDKL great. Please review our [contribution guidelines](CONTRIBUTING.md).

## License
Apache License, please see [LICENSE](LICENSE) for details.
