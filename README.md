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

### Ducklake with an external catalog

For a catalog that isn't a local file — Postgres, MySQL, or one behind a
`ducklake` secret — put the `INSTALL`/`LOAD`/`ATTACH` statements in the **Init
SQL** connection field (see below), not in the SQL editor:

```sql
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
ATTACH IF NOT EXISTS 'ducklake:postgres:dbname=catalog user=me password=secret host=pg port=5432'
  AS my_lake (DATA_PATH 's3://my-bucket/my_lake/');
USE my_lake;
```

## Init SQL

The **Init SQL** connection field runs on every new DuckDB connection Metabase
opens. Use it for whatever a connection needs before it can answer queries:
installing and loading extensions, creating secrets, attaching catalogs.

This matters because Metabase keeps a pool of connections and opens new ones as
it goes. Running `ATTACH` once in the SQL editor only affects the connection
that happened to serve that query. The attached tables can then show up in the
data browser — a sync saw them — but fail with *table does not exist* when a
later query lands on a connection that never ran the `ATTACH`, or after a
restart. Init SQL is what makes an attachment stick.

Statements run as one batch, so keep them ordered and idempotent
(`CREATE OR REPLACE SECRET`, `ATTACH IF NOT EXISTS`, ...).

### Attached catalogs need a search_path

Metabase records a table's schema without its catalog, so a table in an
attached catalog is only reachable if DuckDB can resolve it from the search
path. Set one in Init SQL for every catalog you attach, or queries fail with
`Catalog Error: Table with name <table> does not exist!` even though the table
is listed in the data browser:

```sql
ATTACH IF NOT EXISTS '/data/second.duckdb' AS second;
SET search_path='second.main,main';
```

## Changing the MotherDuck token

DuckDB refuses to open the same database under a different configuration while
connections to it are still open:

```
Connection Error: Can't open a connection to same database file with a
different configuration than existing connections
```

Editing the token on an existing data source therefore does not take effect:
Metabase goes on serving queries from pooled connections that still hold the
old token. **Restart Metabase after changing a MotherDuck token.**

## Docker

Unfortunately, DuckDB plugin doesn't work in the default Alpine based Metabase docker container out of the box due to some glibc problems. But we provide a Dockerfile to create a Docker image of Metabase based on Debian where the DuckDB plugin does work.

On Alpine the native DuckDB library fails to load — `Error loading shared
library libstdc++.so.6` on the first attempt, then `Could not initialize class
org.duckdb.DuckDBNative` on every attempt after. Installing packages does not
fix it: `duckdb_jdbc` ships a glibc build and Alpine is musl. Use a glibc base
image such as the one below.

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