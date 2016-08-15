# docker-hive-dev
Development environment for HIVE-13680.

## Usage

### Build
Docker 1.10 or higher is required to build the image (to use `--build-arg`).

```
docker build -t kliew/hive-dev --build-arg REPO=https://github.com/kliewkliew/hive --build-arg REVISION=rebase-2 --no-cache .
```

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
