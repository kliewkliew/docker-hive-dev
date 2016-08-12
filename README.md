# docker-hive-dev
Apache Hive with remote-debugging in Docker for Hive development

## Usage

### Build
Docker 1.10 or higher is required to build the image (to use `--build-arg`).

To debug your own fork
```
docker build -t kliew/hive-dev --build-arg REPO=https://github.com/kliewkliew/hive .
```

To build a specific revision
```
docker build -t kliew/hive-dev --build-arg REPO=https://github.com/kliewkliew/hive --build-arg REVISION=fb230f9df5b7c990c80326671d9975a6f05e1600 .
```

You may have to build with `--no-cache`  but in most cases you can just specify the revision to use the cache up until the `git clone ~` step, after which the build process will do `git pull` and checkout the specified revision.


#### Build Parameters
* REPO         Hive git fork 
* REVISION     Git revision of $REPO
* HIVE_VERSION Ensure this matches the version string in hive/pom.xml

### Run
```
docker run -it -p $HOST_HIVESERVER2_DEBUG_PORT:8001 kliew/hive-dev
```
Add `-p 10000:10000` if you want access from an external beeline client.

In Eclipse, open Debug Configurations, create Remote Java Application, Host: localhost, Port: $HOST_HIVESERVER2_DEBUG_PORT.

To debug beeline, run the image with `-p $HOST_BEELINE_DEBUG_PORT:8000` and start beeline with:
```
docker exec -it <container id/name> beeline --debug <other options>
```

And debug against $HOST_BEELINE_DEBUG_PORT in Eclipse.

#### Environment Variables (overridable)
* HIVE_OPTS   Override the Hive configuration in the Dockerfile

