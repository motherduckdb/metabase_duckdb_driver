# Metabase DuckDB Driver

The Metabase DuckDB driver allows [Metabase](https://www.metabase.com/) ([GitHub](https://github.com/metabase/metabase)) to connect to [DuckDB](https://duckdb.org/) databases and [MotherDuck](https://motherduck.com/).

This driver is supported by [MotherDuck](https://motherduck.com/). If you would like to open a GitHub issue to report a bug or request new features, or would like to open a pull request, please do so in this repository, and not in the core Metabase GitHub repository.

## About DuckDB

[DuckDB](https://duckdb.org) is an in-process SQL OLAP database. It does not run as a separate process, but embeds directly within the host process—in this case, **Metabase itself** (similar to SQLite).

## Installation

### Where to find the driver

Download the latest `duckdb.metabase-driver.jar` from the [releases page](https://github.com/MotherDuck-Open-Source/metabase_duckdb_driver/releases/latest).

You can find [past releases](https://github.com/MotherDuck-Open-Source/metabase_duckdb_driver/releases), and [releases earlier than 0.2.6](https://github.com/AlexR2D2/metabase_duckdb_driver/releases) (DuckDB v0.10.0) on GitHub.

### Installing the driver

Metabase automatically loads the DuckDB driver if it finds the JAR in the plugins directory at startup. So, you just need to download the JAR and put it in the plugins directory. Below are some examples of where the plugins directory can be located.

**Standard installation:**

```bash
# Example directory structure with DuckDB driver
/app/metabase.jar
/app/plugins/duckdb.metabase-driver.jar
```

**Mac App:**

```bash
~/Library/Application Support/Metabase/Plugins/duckdb.metabase-driver.jar
```

**Docker or custom location:** Set the `MB_PLUGINS_DIR` environment variable. You can also set this if you want to use another directory for plugins.

> **Important:** Restart Metabase after adding or upgrading the driver. Hot-reload is not supported.

## Connecting to MotherDuck

1. In Metabase, go to **Admin Settings** > **Databases** > **Add Database**
2. Select **DuckDB** as the database type
3. Configure the connection:
   - **Database file:** `md:my_database` (your MotherDuck database name)
   - **MotherDuck Token:** paste your token from the [MotherDuck UI](https://app.motherduck.com/)
   - **Enable old_implicit_casting:** keep enabled (recommended for datetime filtering)

> **Note:** Since DuckDB does not do implicit casting by default, `old_implicit_casting` is necessary for datetime filtering in Metabase to work correctly.

## Connecting to a local DuckDB file

1. **Database file:** enter the full path (e.g., `/path/to/database.duckdb`)
2. **Enable old_implicit_casting:** recommended
3. **Read-only:** toggle as needed

> **Note:** DuckDB supports either one read/write process OR multiple read-only processes, but not both simultaneously.

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

**MotherDuck-managed catalog:**

```sql
ATTACH 'ducklake:md:__ducklake_metadata_mydb' AS dl;
```

**Self-managed catalog with S3:**

```sql
ATTACH 'ducklake:/path/to/metadata.ducklake' AS dl (DATA_PATH 's3://bucket/lake/');
```

Then query tables as `dl.my_table`.

## Docker

The DuckDB driver requires glibc and doesn't work with Alpine-based images.

### Dockerfile

Create a `Dockerfile` with the following content:

```dockerfile
FROM eclipse-temurin:21-jre

ENV MB_PLUGINS_DIR=/plugins

RUN mkdir -p ${MB_PLUGINS_DIR} /app

# Download Metabase (use a specific version like v0.57.4 if needed)
ADD https://downloads.metabase.com/latest/metabase.jar /app/metabase.jar

# Download the latest MotherDuck DuckDB driver
ADD https://github.com/MotherDuck-Open-Source/metabase_duckdb_driver/releases/latest/download/duckdb.metabase-driver.jar ${MB_PLUGINS_DIR}/

EXPOSE 3000

CMD ["java", "-jar", "/app/metabase.jar"]
```

### Build and run

```bash
docker build . --tag metabase_duckdb:latest
docker run --name metabase_duckdb -d -p 3000:3000 metabase_duckdb
```

Open Metabase at <http://localhost:3000>. See [Running Metabase on Docker](https://www.metabase.com/docs/latest/installation-and-operation/running-metabase-on-docker) for details.

### Mounting local files

To access local DuckDB or Parquet files:

```bash
docker run -v /local/data:/data -p 3000:3000 metabase_duckdb
```

Then reference files as `/data/myfile.duckdb` or `/data/myfile.parquet`.

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

**Technical background:** DuckDB JDBC 1.3+ introduced a [database instance cache](https://motherduck.com/docs/troubleshooting/error_messages/) that keeps MotherDuck connections alive for 15 minutes by default. This cache keys by database name (not the full URL), so URL parameter workarounds like `?refresh=X` don't bust it.

**Solution:** Add `motherduck_dbinstance_inactivity_ttl=0s` to the **Additional DuckDB connection string options** field (under Advanced options). This disables the instance cache and allows seamless token updates.

**Alternative:** Restart Metabase before updating the token.

## Building from source

Building the driver requires the Metabase source code since Metabase's build toolchain compiles the driver as an uberjar.

### Prerequisites

Choose one of these options:

**Option A: Local build (macOS/Linux)**
- Java 21+ (e.g., `brew install openjdk@21` on macOS)
- [Clojure CLI](https://clojure.org/guides/install_clojure) (e.g., `brew install clojure/tools/clojure` on macOS)

**Option B: DevContainer build**
- [Docker](https://www.docker.com/) installed and running
- VS Code with [DevContainer](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers) extension

### Setup

```bash
# Create a workspace directory
mkdir duckdb_plugin && cd duckdb_plugin

# Clone repositories
git clone https://github.com/metabase/metabase.git
git clone https://github.com/MotherDuck-Open-Source/metabase_duckdb_driver.git

# Copy driver source into Metabase's driver directory
mkdir -p metabase/modules/drivers/duckdb
cp -rf metabase_duckdb_driver/* metabase/modules/drivers/duckdb/
```

Register the driver by adding this line to `metabase/modules/drivers/deps.edn` inside the `:deps` map:

```clojure
metabase/duckdb {:local/root "duckdb"}
```

### Build

**Option A: Local build**

```bash
cd metabase
clojure -X:build:drivers:build/driver :driver :duckdb
```

**Option B: DevContainer build**

1. Copy the DevContainer config: `cp -r metabase_duckdb_driver/.devcontainer .`
2. Open the `duckdb_plugin` folder in VS Code
3. When prompted, click **Reopen in Container**
4. In the DevContainer terminal, run:

```bash
cd metabase
clojure -X:build:drivers:build/driver :driver :duckdb
```

### Output

The built JAR will be at:

```
metabase/resources/modules/duckdb.metabase-driver.jar
```

Copy this file to your Metabase plugins directory to install it.

## Acknowledgement

Thanks [@AlexR2D2](https://github.com/AlexR2D2) for originally authoring this connector.
