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

Unfortunately, DuckDB plugin doesn't work in the default Alpine based Metabase docker container out of the box due to some glibc problems. But we provide a Debian-based Docker image of Metabase where the DuckDB plugin does work.

### Pre-built images

Pre-built images are published to the GitHub Container Registry and are the easiest way to get started:

```bash
# Latest Metabase with the latest DuckDB driver
docker pull ghcr.io/motherduckdb/metabase-duckdb:latest

# Specific Metabase and driver version
docker pull ghcr.io/motherduckdb/metabase-duckdb:0.59.12-duckdb1.5.2.0
```

Tags follow the pattern `<metabase_version>-duckdb<driver_version>`. Browse all available tags at [ghcr.io/motherduckdb/metabase-duckdb](https://github.com/motherduckdb/metabase_duckdb_driver/pkgs/container/metabase-duckdb).

Start the container:

```bash
docker run --name metabase_duckdb -d -p 3000:3000 ghcr.io/motherduckdb/metabase-duckdb:latest
# Then open http://localhost:3000
```

### Building locally

See the included [Dockerfile](./Dockerfile) for a complete setup. You can build the container like so, optionally with specific Metabase or DuckDB driver versions:

```bash
# Build with default versions: the newest Metabase version in
# metabase_versions.json + the driver version pinned in deps.edn
docker build . --tag metabase_duckdb:latest

# Build with a specific Metabase version and driver jar (a release URL or a
# path inside the build context)
docker build . --tag metabase_duckdb:latest \
  --build-arg METABASE_VERSION=0.59.12 \
  --build-arg DUCKDB_DRIVER_URL=https://github.com/motherduckdb/metabase_duckdb_driver/releases/download/1.5.2.0/duckdb.metabase-driver.jar
```

### Publishing new images (maintainers)

Which Metabase versions the driver supports is recorded in
[metabase_versions.json](./metabase_versions.json), a plain list of versions.

Images are built by the same workflow that builds the driver
(`build_metabase_duckdb_driver.yaml`), always from the jar produced in that
run, never from a release download:

- Pull requests push `pr-<number>` (PRs from forks build without pushing).
- Pushes to `main` push `main` and `sha-<short-sha>`.
- Publishing a release attaches the jar to the release and pushes one image per
  listed Metabase version, tagged `<metabase_version>-duckdb<driver_version>`,
  with `:latest` going to the newest listed Metabase version.

**Build Container Images** is a manual backfill tool for combining an
already-published driver release with another Metabase version (e.g. a new
Metabase release for an existing driver). It pulls the driver jar from that
GitHub release and takes these inputs:

| Input | Required | Default | Effect |
| --- | --- | --- | --- |
| `metabase_version` | yes | — | Metabase version, no `v` prefix (e.g. `0.63.10`) |
| `driver_version` | yes | — | Driver version, which must equal its release tag (e.g. `1.5.5.0`) |
| `tag_latest` | no | `false` | Also push `:latest` |
| `force_rebuild` | no | `false` | Overwrite the tag if it already exists |
| `dry_run` | no | `false` | Build both platforms but push nothing |

#### Releasing a new driver version

1. In one PR: bump `org.duckdb/duckdb_jdbc` in [deps.edn](./deps.edn) (this *is* the driver version), and [metabase_versions.json](./metabase_versions.json) if the supported range changed. The Dockerfile defaults to the newest listed Metabase version and the newest driver release, so it needs no edits. Merge to `main`.
2. Publish the release, tagged with the bare new driver version (e.g. `1.5.5.0`), pointing at a commit that contains the updated `metabase_versions.json`. The workflow rejects a tag that does not match the driver version in `deps.edn`. The release run then builds the jar from the tagged commit, attaches it to the release, and pushes the images.

Both the target registry and the driver download URL are derived from the repository the workflow runs in, so the whole pipeline can be exercised on a fork without editing anything.

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
| `MB_VERSION` | newest in `metabase_versions.json` | Metabase release `make dev` runs |
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
