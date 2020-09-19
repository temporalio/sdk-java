# Using BuildKite

BuildKite simply runs Docker containers. So it is easy to perform the 
same build locally that BuildKite will do. To handle this, there are
docker-compose file and the Dockerfile. 

## Testing the build locally
To try out the build locally, start from the root folder of this repo 
and run the following commands.

Build the container for 

build:
```bash
docker-compose -f docker/buildkite/docker-compose.yml build
```

Note that BuildKite will run basically the same commands.

## Testing the build in BuildKite
Creating a PR against the master branch will trigger the BuildKite
build. Members of the Temporal team can view the build pipeline here:
https://buildkite.com/temporal/api

Eventually this pipeline should be made public. It will need to ignore 
third party PRs for safety reasons.
