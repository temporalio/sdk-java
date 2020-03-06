# Temporal Java SDK  [![Build Status](https://badge.buildkite.com/0c96b8b74c0921208e898c10a602e2fe9ecb7641c2befee0e7.svg?theme=github&branch=master)](https://buildkite.com/uberopensource/cadence-java-client) [![Javadocs](https://www.javadoc.io/badge/io.temporal/cadence-client.svg)](https://www.javadoc.io/doc/io.temporal/cadence-client)


[Temporal](https://github.com/temporalio/temporal) is a distributed, scalable, durable, and highly available orchestration engine we developed at Uber Engineering to execute asynchronous long-running business logic in a scalable and resilient way.

`temporal-client` is the framework for authoring workflows and activities in Java.

If you are authoring in Go, see [Go Temporal Client](https://github.com/temporalop/temoral-go-client).

## Samples

For samples, see [Samples for the Java Temporal client](https://github.com/temporalio/temporal-java-samples).

## Run Temporal Server

Run Temporal Server using Docker Compose:

    curl -O https://raw.githubusercontent.com/temporalio/temporal/master/docker/docker-compose.yml
    docker-compose up

If this does not work, see instructions for running the Temporal Server at https://github.com/temporalio/temporal/blob/master/README.md.

## Get CLI

[CLI is avaialable as an executable or as a docker image](https://github.com/temporalio/temporal/blob/master/tools/cli/README.md)

## Build a configuration

Add *temporal-client* as a dependency to your *pom.xml*:

    <dependency>
      <groupId>io.temporal</groupId>
      <artifactId>temporal-client</artifactId>
      <version>1.0.0</version>
    </dependency>
    
or to *build.gradle*:

    compile group: 'io.temporal', name: 'temporal-client', version: '1.0.0'

## Documentation

The documentation on how to use the Temporal Java client is [here](https://temporal.io/docs/06_javaclient/).

Javadocs for the client API are located [here](https://www.javadoc.io/doc/io.temporal/temporal-client).

## Contributing
We'd love your help in making the Temporal Java client great. Please review our [contribution guidelines](CONTRIBUTING.md).

## License
Apache License, please see [LICENSE](LICENSE) for details.
