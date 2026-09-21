# Metabase DuckDB Driver

The Metabase DuckDB driver allows [Metabase](https://www.metabase.com/) ([GitHub](https://github.com/metabase/metabase)) to use the embedded [DuckDB](https://duckdb.org/) ([GitHub](https://github.com/duckdb/duckdb)) database.

This driver is supported by [MotherDuck](https://motherduck.com/). If you would like to open a GitHub issue to report a bug or request new features, or would like to open a pull requests against it, please do so in this repository, and not in the core Metabase GitHub repository.

## DuckDB

[DuckDB](https://duckdb.org) is an in-process SQL OLAP database management. It does not run as a separate process, but completely embedded within a host process. So, it **embedds to the Metabase process** like SQLite.

## Obtaining the DuckDB Metabase driver

### Where to find it

[Click here](https://github.com/MotherDuck-Open-Source/metabase_duckdb_driver/releases/latest) to view the latest release of the Metabase DuckDB driver; click the link to download `duckdb.metabase-driver.jar`.

You can find past releases of the DuckDB driver [here](https://github.com/MotherDuck-Open-Source/metabase_duckdb_driver/releases), and releases earlier than 0.2.6 (corresponding to DuckDB v0.10.0) [here](https://github.com/AlexR2D2/metabase_duckdb_driver/releases).

### How to Install it

Metabase will automatically make the DuckDB driver available if it finds the driver in the Metabase plugins directory when it starts up.
All you need to do is create the directory `plugins` (if it's not already there), move the JAR you just downloaded into it, and restart Metabase.

By default, the plugins directory is called `plugins`, and lives in the same directory as the Metabase JAR.

For example, if you're running Metabase from a directory called `/app/`, you should move the DuckDB driver to `/app/plugins/`:

```bash
# example directory structure for running Metabase with DuckDB support
/app/metabase.jar
/app/plugins/duckdb.metabase-driver.jar
```

If you're running Metabase from the Mac App, the plugins directory defaults to `~/Library/Application Support/Metabase/Plugins/`:

```bash
# example directory structure for running Metabase Mac App with DuckDB support
/Users/you/Library/Application Support/Metabase/Plugins/duckdb.metabase-driver.jar
```

If you are running the Docker image or you want to use another directory for plugins, you should specify a custom plugins directory by setting the environment variable `MB_PLUGINS_DIR`.

## Configuring

Once you've started up Metabase, go to add a database and select "DuckDB". Provide the path to the DuckDB database file. To use DuckDB in the in-memory mode without any database file, you can specify `:memory:` as the database path. 

## Parquet

Does it make sense to start DuckDB Database in-memory mode without any data in system like Metabase? Of Course yes!
Because of feature of DuckDB allowing you [to run SQL queries directly on Parquet files](https://duckdb.org/2021/06/25/querying-parquet.html). So, you don't need a DuckDB database.

For example (somewhere in Metabase SQL Query editor):

```sql
# DuckDB selected as source

SELECT originalTitle, startYear, genres, numVotes, averageRating from '/Users/you/movies/title.basics.parquet' x
JOIN (SELECT * from '/Users/you/movies/title.ratings.parquet') y ON x.tconst = y.tconst
ORDER BY averageRating * numVotes DESC
```

## Ducklake

Starting from driver version 1.4.1.0, you can configure the DuckDB data source to point to a ducklake database by setting the database file field to `ducklake:/path/to/db_name.ducklake`. This will also create a folder `/path/to/db_name.ducklake.files`, where the parquet files are stored.

Right now, specifying alternative data path for a brand new ducklake database, like `ATTACH 'ducklake:my_other_ducklake.ducklake' AS my_other_ducklake (DATA_PATH 'some/other/path/');` is not natively supported. But you can first initialize the ducklake in SQL, using another duckdb client or within the Metabase SQL interface, with the target data path, then create the data source attaching the ducklake database already initialized with the target data path. 

### MotherDuck-hosted Ducklake
If you're using a ducklake database on MotherDuck, it can be attached like a regular MotherDuck database, e.g. `md:my_ducklake_database`. 


## Docker

Unfortunately, DuckDB plugin doesn't work in the default Alpine based Metabase docker container out of the box due to some glibc problems. But we provide a Dockerfile to create a Docker image of Metabase based on Debian where the DuckDB plugin does work.

See the included [Dockerfile](./Dockerfile) for a complete setup. You can build the container like so, optionally with specific Metabase or DuckDB driver versions:

```bash
# Build with default versions (see Dockerfile for the defaults)
docker build . --tag metabase_duckdb:latest

# Build with specific versions
docker build . --tag metabase_duckdb:latest \
  --build-arg METABASE_VERSION=0.58.9 \
  --build-arg METABASE_DUCKDB_DRIVER_VERSION=1.4.3.1
```

Then start the container:
```bash
docker run --name metabase_duckdb -d -p 3000:3000 metabase_duckdb
```

Now open Metabase in the browser: http://localhost:3000. For detailed instructions on running the container, please see the official guide for [Running Metabase on Docker](https://www.metabase.com/docs/latest/installation-and-operation/running-metabase-on-docker).



### Using DB file with Docker

In order to use the DuckDB database file from your local host in the docker container you should mount folder with your DB file into docker container

```bash
docker run -v /dir_with_my_duck_db_file_in_the_local_host/:/container/directory ...
```

Next, in the settings page of DuckDB of Metabase Web UI you could set your DB file name like this

```bash
/container/directory/<you_duckdb_file>
```

The same way you could mount the dir with parquet files into container and make SQL queries to this files using directory in your container.

## Development

Everything runs in Docker; no local JDK, Clojure or Node needed.

```bash
make dist   # build dist/duckdb.metabase-driver.jar from this working tree
make dev    # run Metabase with that jar on http://localhost:3000
make test   # run the driver test suite
make stop   # stop the dev Metabase
```

`make dev` keeps its Metabase application database in `./data`, so connections
and questions survive a restart. Drop `.duckdb` or `.parquet` files in `./data`
to reach them from Metabase as `/home/metabase/data/<file>`. If
`motherduck_token` is set in your shell it is passed through, so `md:` works
with an empty token field.

To iterate on the driver: edit `src/`, then run `make dev` again — it rebuilds
the jar and restarts Metabase in about a minute. The Metabase checkout and the
Maven cache live in `~/.cache/metabase-duckdb-driver` and are shared by every
worktree of this repo, so a second branch does not re-download them.

| Variable | Default | Purpose |
| --- | --- | --- |
| `MB_VERSION` | `0.58.9` | Metabase release `make dev` runs |
| `MB_REF` | `master` | Metabase revision the driver is built and tested against |
| `DRIVER_VERSION` | *(unset)* | Use a published driver release instead of a local build |
| `DRIVERS` | `duckdb` | `make test DRIVERS=motherduck` runs the suite against MotherDuck (needs `motherduck_token`) |
| `TEST` | *(unset)* | `make test TEST=metabase.driver.duckdb-test` for a single namespace |

```bash
make repl                       # Metabase REPL with the driver on the classpath
make dev MB_VERSION=0.63.1      # reproduce a version-specific report
make dev DRIVER_VERSION=1.5.4   # ... against a released driver, to bisect a regression
make clean                      # drop dist/ and the dev container
make distclean                  # also drop the cached Metabase checkout
```

A VS Code [DevContainer](.devcontainer) is also available if you prefer working
inside the Metabase checkout directly.


## Acknowledgement

Thanks [@AlexR2D2](https://github.com/AlexR2D2) for originally authoring this connector.