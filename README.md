# Temporal Java SDK  [![Build status](https://badge.buildkite.com/663f6d1be81be6700c28c242b35905f20b68c4fda7b2c7c4e3.svg?branch=master)](https://buildkite.com/temporal/java-sdk-public) [![Coverage Status](https://coveralls.io/repos/github/temporalio/sdk-java/badge.svg?branch=master)](https://coveralls.io/github/temporalio/sdk-java?branch=master)

[Temporal](https://github.com/temporalio/temporal) is a Workflow-as-Code platform for building and operating
resilient applications using developer-friendly primitives, instead of constantly fighting your infrastructure.

The Java SDK is the framework for authoring Workflows and Activities in Java. (For other languages, see [Temporal SDKs](https://docs.temporal.io/application-development).)

Java SDK:

- [Java SDK documentation](https://docs.temporal.io/docs/java/introduction)
- [Javadoc API reference](https://www.javadoc.io/doc/io.temporal/temporal-sdk/latest/index.html)
- [Sample applications](https://github.com/temporalio/samples-java#samples-directory)

Temporal in general:

- [Temporal docs](https://docs.temporal.io/)
- [Install Temporal Server](https://docs.temporal.io/docs/server/quick-install)
- [Temporal CLI](https://docs.temporal.io/docs/devtools/tctl/)

## Requirements

- Java 1.8+

## Build configuration

[Find the latest release](https://search.maven.org/artifact/io.temporal/temporal-sdk) of the Temporal Java SDK at maven central.

Add *temporal-sdk* as a dependency to your *pom.xml*:

    <dependency>
      <groupId>io.temporal</groupId>
      <artifactId>temporal-sdk</artifactId>
      <version>N.N.N</version>
    </dependency>

or to *build.gradle*:

    compile group: 'io.temporal', name: 'temporal-sdk', version: 'N.N.N'

## macOS Users

Due to issues with default hostname resolution
(see [this StackOverflow question](https://stackoverflow.com/questions/33289695/inetaddress-getlocalhost-slow-to-run-30-seconds) for more details),
macOS Users may see gRPC `DEADLINE_EXCEEDED` errors and other slowdowns when running the SDK.

To solve the problem add the following entries to your `/etc/hosts` file (where my-macbook is your hostname):

```conf
127.0.0.1   my-macbook
::1         my-macbook
```

## Contributing

We'd love your help in improving the Temporal Java SDK. Please review our [contribution guidelines](CONTRIBUTING.md).

## License

Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.

Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.

Modifications copyright (C) 2017 Uber Technologies, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this material except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.