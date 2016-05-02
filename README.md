# docker-hive-dev
Apache Hive with remote-debugging in Docker for Hive development

## Usage

### Build
Docker 1.10 or higher is required to build the image (to use `--build-arg`).

To debug your own fork
```
docker build -t kliew/hive-dev --build-arg REPO=https://github.com/kliewkliew/hive .
```

To debug from the official repo (ie. for comparing performance of a specific revisions)
```
docker build -t kliew/hive-dev --build-arg REVISION=fb230f9df5b7c990c80326671d9975a6f05e1600 .
```

#### Build Parameters
* REPO        Hive git fork 
* REVISION    Git revision of $REPO

### Run
```
docker run -it -p $HOST_DEBUG_PORT:8000 kliew/hive
```
Add `-p 10000:10000` if you want access from an external beeline client.

#### Environment Variables (overridable)
* DEBUG_PORT  The port to use for debugging Hive. In Eclipse, open Debug Configurations, create Remote Java Application, Host: localhost, Port: $HOST_DEBUG_PORT.
* HIVE_OPTS   Override the Hive configuration in the Dockerfile

