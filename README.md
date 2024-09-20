# Metabase DuckDB Driver

The Metabase DuckDB driver allows [Metabase](https://www.metabase.com/) ([GitHub](https://github.com/metabase/metabase)) to use the embedded [DuckDB](https://duckdb.org/) ([GitHub](https://github.com/duckdb/duckdb)) database.

This driver is supported by [MotherDuck](https://motherduck.com/). If you would like to open a GitHub issue to report a bug or request new features, or would like to open a pull requests against it, please do so in this repository, and not in the core Metabase GitHub repository.

## DuckDB

[DuckDB](https://duckdb.org) is an in-process SQL OLAP database management. It does not run as a separate process, but completely embedded within a host process. So, it **embedds to the Metabase process** like Sqlite.

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

## Docker

Unfortunately, DuckDB plugin does't work in the default Alpine based Metabase docker container due to some glibc problems. But thanks to @ChrisH and @lucmartinon we have simple Dockerfile to create Docker image of Metabase based on Debian where the DuckDB plugin does work.

```bash
FROM openjdk:19-buster

ENV MB_PLUGINS_DIR=/home/plugins/

ADD https://downloads.metabase.com/v0.50.26/metabase.jar /home
ADD https://github.com/MotherDuck-Open-Source/metabase_duckdb_driver/releases/download/0.2.10/duckdb.metabase-driver.jar /home/plugins/

RUN chmod 744 /home/plugins/duckdb.metabase-driver.jar

CMD ["java", "-jar", "/home/metabase.jar"]
```

Build the image:
```bash
docker build . --tag metaduck:latest`
```

Then create the container:
```bash
docker run --name metaduck -d -p 80:3000 -m 2GB -e MB_PLUGINS_DIR=/home/plugins metaduck
```

Open Metabase in the browser: http://localhost

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
9. Create duckdb driver directory in the cloned metabase sourcecode:
```
> mkdir -p duckdb_plugin/metabase/modules/drivers/duckdb
```
10. Copy the `metabase_duckdb_driver` source code into created dir
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

Thanks @AlexR2D2 for originally authoring this connector.
