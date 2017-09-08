# Preparation

First of all you need to copy `config.sample.clj` to `config.clj` and
adjust it according to your needs. However, don't change the `:db`
settings as those will be provided via environment variables at
runtime (see below). Also, you should not change the `:port` setting
in the `:web` and `:printing :lpd` config sections unless you know
what you're doing. You will learn below how you can expose Pepa's web
and LPD servers under a different ports.

# Using `docker-compose`

The simplest way to create an up and running Pepa system is via
[`docker-compose`](https://docs.docker.com/compose/) which will
instantiate a PostgreSQL container linked to a Pepa container in one
go for you. Simply run the following command from within this
directory:

    docker-compose up -d

This will build and start Pepa in the default configuration. You can
inspect the log output of the processes by running:

    docker-compose logs -f

If all went well you should see a line like this:

    web_1  | 14:17:19.974 INFO  [pepa.web] Started web server on http://0.0.0.0:4035/


Now you should be able to reach Pepa's web interface at
http://localhost:4035/. Refer to the
[documentation of `docker-compose`](https://docs.docker.com/compose/)
for more details on how to operate this setup.

## Providing an external database volume

The default setup will put Pepa's database in Docker's internal
storage. To provide an external volume, run a command like this:

    PEPA_DB_VOLUME=/some/path/for/pepa/db docker-compose -f docker-compose.yml -f docker-compose.db-volume.yml up

That will mount the path given in `PEPA_DB_VOLUME` as the storage
volume of the database container.

## Customizing Pepa's web port

The port Pepa's web server is exposed under on your host system
(default: 4035) can be customized by passing a port number as the
`PEPA_WEB_PORT` environment variable, e.g.:

    PEPA_WEB_PORT=8080 docker-compose up -d

## Customizing Pepa's LPD port

If you've enabled Pepa's LPD server in `config.clj`, the port it is
exposed under on your host system can be customized by passing a port
number as the `PEPA_LPD_PORT` environment variable, e.g.:

    PEPA_LPD_PORT=6213 docker-compose up -d

# Using Docker directly

The `Dockerfile` provided in this repository builds a Docker image
that only contains the Pepa service, i.e. it relies on a PostgreSQL
9.6 to be provided to it via the environment variables `DB_HOST`,
`DB_PORT`, `DB_USER`, `DB_PASS`, and `DB_NAME`. The web server listens
on port 4035 inside the container and needs to be exposed to the host
in order to be useful. Example:

    docker run -e DB_USER=pepa -e DB_PASS=foobar -e DB_NAME=pepa -e DB_HOST=10.10.10.123 -e DB_PORT=5432 -p 127.0.0.1:8080:4035 -d pepa

This will build and start a container named `pepa` which will connect
to a PostgreSQL server at `10.10.10.123:5432` with the user `pepa`
using the password `foobar`. It assumes the `pepa` database to exist
and requires permissions to load a schema into it (i.e. create tables,
functions, indices, etc.). It will do so automatically on startup in
case the database is empty. If it's not empty, it assumes that the
schema is already loaded. Pepa's web interface will then be available
on http://127.0.0.1:8080 once the service has started up.
