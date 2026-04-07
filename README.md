# Metabase DuckDB Driver

The Metabase DuckDB driver allows [Metabase](https://www.metabase.com/) ([GitHub](https://github.com/metabase/metabase)) to connect to [DuckDB](https://duckdb.org/) databases and [MotherDuck](https://motherduck.com/).

This driver is supported by [MotherDuck](https://motherduck.com/). File issues or pull requests in this repository, not in the core Metabase GitHub repository.

## About DuckDB

[DuckDB](https://duckdb.org) is an in-process SQL OLAP database. It does not run as a separate process, but embeds directly within the host process—in this case, Metabase itself (similar to SQLite).

## Installation

### Where to find the driver

Download the latest `duckdb.metabase-driver.jar` from the [releases page](https://github.com/motherduckdb/metabase_duckdb_driver/releases/latest).

You can find [past releases](https://github.com/motherduckdb/metabase_duckdb_driver/releases), and [releases earlier than 0.2.6](https://github.com/AlexR2D2/metabase_duckdb_driver/releases) (DuckDB v0.10.0) on GitHub.

### Installing the driver

Metabase automatically loads the DuckDB driver if it finds the JAR in the plugins directory at startup. Download the JAR and place it in the plugins directory. Examples:

Standard installation:

```bash
# Example directory structure with DuckDB driver
/app/metabase.jar
/app/plugins/duckdb.metabase-driver.jar
```

Mac App:

```bash
~/Library/Application Support/Metabase/Plugins/duckdb.metabase-driver.jar
```

Docker or custom location: Set `MB_PLUGINS_DIR` to use a custom plugins directory.

> Important: Restart Metabase after adding or upgrading the driver. Hot-reload is not supported.

## Connecting to MotherDuck

1. In Metabase, go to Admin Settings > Databases > Add Database
2. Select DuckDB as the database type
3. Configure the connection:
   - Database file: `md:my_database` (your MotherDuck database name)
   - MotherDuck Token: paste your token from the [MotherDuck UI](https://app.motherduck.com/)
   - Enable old_implicit_casting: keep enabled (recommended for datetime filtering)

> Note: DuckDB does not do implicit casting by default, so `old_implicit_casting` is required for datetime filtering in Metabase.

## Connecting to a local DuckDB file

1. Database file: enter the full path (e.g., `/path/to/database.duckdb`)
2. Enable old_implicit_casting: recommended
3. Read-only: toggle as needed

> Note: DuckDB supports either one read/write process OR multiple read-only processes, but not both simultaneously.

## Connection options

The driver currently exposes these configuration fields in Metabase:

| Option | Purpose |
|-------|----------|
| Database file | Local DuckDB path, `md:my_db`, `ducklake:/path/to/db.ducklake`, or `:memory:` |
| Read-only mode | Opens local DuckDB files in read-only mode |
| Enable old_implicit_casting | Recommended for Metabase datetime filters |
| Allow unsigned extensions | Permits loading unsigned DuckDB extensions |
| Memory limit | Sets DuckDB `memory_limit` (for example `1GB`) |
| Azure transport option type | Passes through DuckDB Azure transport settings |
| MotherDuck Token | Secret used for `md:` connections |
| Init SQL | Runs on each new connection, useful for `ATTACH`, `INSTALL`, or `LOAD` statements |
| Additional DuckDB connection string options | Extra JDBC URL parameters such as `http_keep_alive=false` |

`Init SQL` is especially useful for DuckLake attachments and external object-store access, because the driver reruns it for new pooled connections.

## In-memory mode

Set the database file to `:memory:` to use DuckDB without a persistent file. This is useful for querying external files directly.

### Querying Parquet files

You can query Parquet files directly without a database:

```sql
SELECT originalTitle, startYear, genres, numVotes, averageRating 
FROM '/path/to/title.basics.parquet' x
JOIN '/path/to/title.ratings.parquet' y ON x.tconst = y.tconst
ORDER BY averageRating * numVotes DESC
```

## DuckLake

Starting from driver version 1.4.1.0, you can connect to DuckLake databases.

### Local DuckLake

Set the database file to `ducklake:/path/to/db_name.ducklake`. This creates a folder `/path/to/db_name.ducklake.files` for parquet storage.

For custom data paths, first create the DuckLake database using another DuckDB client with your desired `DATA_PATH`, then attach it in Metabase.

### MotherDuck-hosted DuckLake

Connect the same way as any MotherDuck database: set database file to `md:my_ducklake_database`.

### Attaching DuckLake via Init SQL

For DuckLake catalogs stored externally, use Init SQL:

MotherDuck-managed catalog:

```sql
ATTACH 'ducklake:md:__ducklake_metadata_mydb' AS dl;
```

Self-managed catalog with S3:

```sql
ATTACH 'ducklake:/path/to/metadata.ducklake' AS dl (DATA_PATH 's3://bucket/lake/');
```

Then query tables as `dl.my_table`.

## Docker

The DuckDB driver requires glibc and doesn't work with Alpine-based images.

Use the included `Dockerfile`, which builds a Debian/Ubuntu-based Metabase image with the DuckDB driver preinstalled.

### Build and run

```bash
# Build with default versions from the Dockerfile
docker build . --tag metabase_duckdb:latest

# Or build with specific versions
docker build . --tag metabase_duckdb:latest \
  --build-arg METABASE_VERSION=0.58.9 \
  --build-arg METABASE_DUCKDB_DRIVER_VERSION=1.4.3.1

docker run --name metabase_duckdb -d -p 3000:3000 metabase_duckdb
```

Open Metabase at <http://localhost:3000>. See [Running Metabase on Docker](https://www.metabase.com/docs/latest/installation-and-operation/running-metabase-on-docker) for details.

### Mounting local files

To access local DuckDB or Parquet files:

```bash
docker run \
  -v /local/data:/home/metabase/data \
  -p 3000:3000 \
  metabase_duckdb
```

Then reference files as `/home/metabase/data/myfile.duckdb` or `/home/metabase/data/myfile.parquet`.

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Driver not detected | Ensure JAR is in plugins directory and restart Metabase |
| Connection failures | Verify database path (local) or database name + token (MotherDuck) |
| Permission errors | Check file permissions for local databases |
| Datetime filtering issues | Enable `old_implicit_casting` in connection settings |
| Token update fails with "same database file" error | See token update instructions below |

### Updating the MotherDuck token

When updating the MotherDuck token, you may see: `Connection error: Can't open a connection to same database file with a different configuration`.

Solution: add `motherduck_dbinstance_inactivity_ttl=0s` in the `Additional DuckDB connection string options` field. This disables the instance cache and allows token updates.

## Building from source

Building the driver requires the Metabase source code since Metabase's build toolchain compiles the driver as an uberjar.

### Prerequisites

Choose one of these options:

Option A: Local build (macOS/Linux)

- Java 21+ (e.g., `brew install openjdk@21` on macOS)
- [Clojure CLI](https://clojure.org/guides/install_clojure) (e.g., `brew install clojure/tools/clojure` on macOS)

Option B: DevContainer build

- [Docker](https://www.docker.com/) installed and running
- VS Code with [DevContainer](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers) extension

### Setup

```bash
# Create a workspace directory
mkdir duckdb_plugin && cd duckdb_plugin

# Clone repositories
git clone https://github.com/metabase/metabase.git
git clone https://github.com/motherduckdb/metabase_duckdb_driver.git

# Copy driver source into Metabase's driver directory
mkdir -p metabase/modules/drivers/duckdb
cp -rf metabase_duckdb_driver/* metabase/modules/drivers/duckdb/
```

Register the driver in the Metabase checkout:

```bash
cd metabase
git apply ./modules/drivers/duckdb/ci/metabase_drivers_deps.patch
```

### Build

Option A: local build

```bash
cd metabase
./bin/build-driver.sh duckdb
```

Option B: DevContainer build

1. Copy the DevContainer config: `cp -r metabase_duckdb_driver/.devcontainer .`
2. Open the `duckdb_plugin` folder in VS Code
3. When prompted, click `Reopen in Container`
4. In the DevContainer terminal, run:

```bash
cd metabase
git apply ./modules/drivers/duckdb/ci/metabase_drivers_deps.patch
./bin/build-driver.sh duckdb
```

### Output

The built JAR will be at:

```bash
metabase/resources/modules/duckdb.metabase-driver.jar
```

Copy this file to your Metabase plugins directory to install it.

### Running tests

The repository CI currently does all of the following:

- Builds the driver against a fresh `metabase/metabase` checkout
- Runs MotherDuck integration tests with `DRIVERS=motherduck`
- Runs local DuckDB integration tests with `DRIVERS=duckdb`

To mirror that setup locally from the `metabase` checkout:

```bash
git apply ./modules/drivers/duckdb/ci/metabase_test_deps.patch
MB_EDITION=ee yarn build-static-viz
DRIVERS=motherduck clojure -X:dev:drivers:drivers-dev:ee:ee-dev:test
DRIVERS=duckdb clojure -X:dev:drivers:drivers-dev:ee:ee-dev:test
```

The MotherDuck test run expects the `motherduck_token` environment variable to be set.

## Acknowledgement

Thanks [@AlexR2D2](https://github.com/AlexR2D2) for originally authoring this connector.
