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
docker build -t kliew/hive-dev --build-arg REVISION=fb230f9df5b7c990c80326671d9975a6f05e1600 .
```

#### Build Parameters
* REPO         Hive git fork 
* REVISION     Git revision of $REPO
* HIVE_VERSION Ensure this matches the current version number

### Run
```
docker run -it -p $HOST_DEBUG_PORT:8000 kliew/hive-dev
```
Add `-p 10000:10000` if you want access from an external beeline client.

In Eclipse, open Debug Configurations, create Remote Java Application, Host: localhost, Port: $HOST_DEBUG_PORT.

#### Environment Variables (overridable)
* HIVE_OPTS   Override the Hive configuration in the Dockerfile

