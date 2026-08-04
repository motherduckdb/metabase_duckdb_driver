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
# Build with default versions (see Dockerfile for the defaults)
docker build . --tag metabase_duckdb:latest

# Build with specific versions
docker build . --tag metabase_duckdb:latest \
  --build-arg METABASE_VERSION=0.59.12 \
  --build-arg METABASE_DUCKDB_DRIVER_VERSION=1.5.2.0
```

### Publishing new images (maintainers)

Images are published by dispatching **Build Container Images** manually, one image per run. There is no stored matrix — every published image is an explicit choice of Metabase version plus driver version:

| Input | Required | Default | Effect |
| --- | --- | --- | --- |
| `metabase_version` | yes | — | Metabase version, no `v` prefix (e.g. `0.62.7`) |
| `driver_version` | yes | — | Driver version, which must equal its release tag (e.g. `1.5.4.0`) |
| `tag_latest` | no | `false` | Also push `:latest` |
| `force_rebuild` | no | `false` | Overwrite the tag if it already exists |
| `dry_run` | no | `false` | Build both platforms but push nothing |

The tag is always derived as `<metabase_version>-duckdb<driver_version>`, so it cannot drift from the versions it names. Both JARs are checked to exist before the build starts, which turns a typo into a fast, named failure rather than a 404 partway through a multi-platform build.

`:latest` is opt-in per run and is never inferred, so that rebuilding an older combination cannot repoint it at an old Metabase. The tradeoff is that **`tag_latest` is a release-day checklist item**: forget it and `:latest` silently stays on the previous version.

Only build combinations known to work together. Metabase 0.63 is deliberately unpublished: v63 passes namespaced keywords in connection details, which driver 1.5.4.0 forwards to DuckDB as JDBC properties and fails to connect ([#103](https://github.com/motherduckdb/metabase_duckdb_driver/issues/103)). The fix is on `main` but unreleased, so 0.63 images should not be built until the release that carries it exists.

#### Releasing a new driver version

1. Publish the release. Its tag must point at a commit with a successful `build_metabase_duckdb_driver.yaml` run, since the release-asset workflow fetches that artifact by commit SHA.
2. **Add .jar file to a release** runs on `release: published` and attaches `duckdb.metabase-driver.jar`.
3. Once that JAR is attached, dispatch **Build Container Images** once per Metabase version you want paired with the new driver, checking `tag_latest` on the newest one.

Step 3 depends on step 2: the image build downloads the driver JAR from the release URL, so dispatching early fails the verify step with `No driver JAR at ...`. This ordering used to be enforced automatically by chaining the image build off the release-asset workflow with `workflow_run`, which cannot pass inputs; it is now the dispatcher's responsibility.

Pairing an existing driver with a new Metabase version needs no release at all — just dispatch **Build Container Images** with that Metabase version and the already-published driver version.

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

## How to build the DuckDB .jar plugin yourself

1. Install VS Code with [DevContainer](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers) extension (see [details](https://code.visualstudio.com/docs/devcontainers/containers))
2. Create some folder, let's say `duckdb_plugin`
3. Clone the `metabase_duckdb_driver` repository into `duckdb_plugin` folder
4. Copy `.devcontainer` from `duckdb_plugin/metabase_duckdb_driver` into `duckdb_plugin`
5. Clone the `metabase` repository of version you need into `duckdb_plugin` folder
6. Now content of the `duckdb_plugin` folder should looks like this:
```
  ..
  .devcontainer
  metabase
  metabase_duckdb_driver
```
7. Add duckdb record to the deps file `duckdb_plugin/metabase/modules/drivers/deps.edn`
The end of the file sholud looks like this:
```
  ...
  metabase/sqlserver          {:local/root "sqlserver"}
  metabase/vertica            {:local/root "vertica"}
  metabase/duckdb             {:local/root "duckdb"}}}  <- add this!
```
8. Set the DuckDB version you need in the `duckdb_plugin/metabase_duckdb_driver/deps.edn`
9. Create duckdb driver directory in the cloned metabase sourcecode (or symlink to where the driver is):
```
> mkdir -p duckdb_plugin/metabase/modules/drivers/duckdb
```
10. Copy the `metabase_duckdb_driver` source code into created dir (skip this if symlinked)
```
> cp -rf duckdb_plugin/metabase_duckdb_driver/* duckdb_plugin/metabase/modules/drivers/duckdb/
```
11. Open `duckdb_plugin` folder in VSCode using DevContainer extension (vscode will offer to open this folder using devcontainer). Wait until all stuff will be loaded. At the end you will get the terminal opened directly in the VS Code, smth like this:
```
vscode ➜ /workspaces/duckdb_plugin $
```
12. Build the plugin
```
vscode ➜ /workspaces/duckdb_plugin $ cd metabase
vscode ➜ /workspaces/duckdb_plugin $ clojure -X:build:drivers:build/driver :driver :duckdb
```
13. jar file of DuckDB plugin will be generated here duckdb_plugin/metabase/resources/modules/duckdb.metabase-driver.jar


## Acknowledgement

Thanks [@AlexR2D2](https://github.com/AlexR2D2) for originally authoring this connector.