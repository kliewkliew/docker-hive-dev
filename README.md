# docker-hive-dev
Apache Hive in Docker for Hive development

## Usage

### Build
Docker 1.10 or higher is required to build the image (because of `--build-arg`).

To debug your own fork
```
docker build -t kliew/hive --build-arg REPO=https://github.com/kliewkliew/hive .
```

To debug from the official repo (ie. for comparing performance of a specific revisions)
```
docker build -t kliew/hive --build-arg REVISION=fb230f9df5b7c990c80326671d9975a6f05e1600 .
```

#### Build Parameters
* REPO        Hive git fork 
* REVISION    Git revision of $REPO
* DEBUG_PORT  The port to use for debugging Hive. In Eclipse, open Debug Configurations, create Remote Java Application, Host: localhost, Port: 8000.
* HIVE_OPTS   Override the default Hive configuration

### Run

To debug locally
```
docker run -it kliew/hive-dev
```

To host the container in a VM and debug remotely
```
docker run -it -p 10000:10000 -p $HOST_DEBUG_PORT:$CONTAINER_DEBUG_PORT kliew/hive
```

