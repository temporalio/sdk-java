# Using BuildKite

BuildKite simply runs Docker containers. So it is easy to perform the 
same build locally that BuildKite will do. To handle this, there are 
two different docker-compose files: one for BuildKite and one for local.
The Dockerfile is the same for both. 

## Testing the build locally
To try out the build locally, start from the root folder of this repo 
(temporal-java-client) and run the following commands.

Build the container for 

unit tests:
```bash
docker-compose -f docker/buildkite/docker-compose-local.yml build unit-test-test-service
```

unit tests with docker sticky on:
```bash
docker-compose -f docker/buildkite/docker-compose-local.yml build unit-test-docker-sticky-on
```

unit tests with docker sticky off:
```bash
docker-compose -f docker/buildkite/docker-compose-local.yml build unit-test-docker-sticky-off
```

Run the integration tests:

unit tests:
```bash
docker-compose -f docker/buildkite/docker-compose-local.yml run unit-test-test-service
```

unit tests with docker sticky on:
```bash
docker-compose -f docker/buildkite/docker-compose-local.yml run unit-test-docker-sticky-on
```

unit tests with docker sticky off:
```bash
docker-compose -f docker/buildkite/docker-compose-local.yml run unit-test-docker-sticky-off
```

Note that BuildKite will run basically the same commands.

## Testing the build in BuildKite
Creating a PR against the master branch will trigger the BuildKite
build. Members of the Temporal team can view the build pipeline here:
https://buildkite.com/temporal/temporal-java-client

Eventually this pipeline should be made public. It will need to ignore 
third party PRs for safety reasons.
