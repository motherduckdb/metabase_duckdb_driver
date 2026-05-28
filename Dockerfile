# Debian (glibc) Metabase image bundled with the DuckDB driver.
#
# This intentionally does NOT use the official metabase/metabase image,
# which is Alpine-based (musl libc). The default duckdb.metabase-driver.jar
# downloaded below ships a glibc-linked DuckDB JNI binary and will fail to
# load on Alpine. If you need to run on the official Alpine image instead,
# use one of the musl-classified jars from the release page:
#   - duckdb-linux_amd64_musl.metabase-driver.jar  (x86_64 Alpine)
#   - duckdb-linux_arm64_musl.metabase-driver.jar  (arm64 Alpine)
# See README.md ("Where to find it") for details.
FROM eclipse-temurin:21-jre-jammy

# Build arguments for versions
ARG METABASE_VERSION=0.58.9
ARG METABASE_DUCKDB_DRIVER_VERSION=1.4.3.1

ENV MB_PLUGINS_DIR=/home/metabase/plugins/

RUN groupadd -r metabase && useradd -r -g metabase metabase

RUN apt-get update && apt-get install -y \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# /home/metabase/plugins will hold the plugins
# /home/metabase/data is not strictly necessary, can be a place to store data files (.duckdb, .parquet, etc)
RUN mkdir -p /home/metabase/plugins /home/metabase/data && \
    chown -R metabase:metabase /home/metabase

WORKDIR /home/metabase
ADD --chown=metabase:metabase https://downloads.metabase.com/v${METABASE_VERSION}/metabase.jar /home/metabase/
ADD --chown=metabase:metabase https://github.com/motherduckdb/metabase_duckdb_driver/releases/download/${METABASE_DUCKDB_DRIVER_VERSION}/duckdb.metabase-driver.jar /home/metabase/plugins/

# Ensure proper file permissions
RUN chmod 755 /home/metabase/metabase.jar && \
    chmod 755 /home/metabase/plugins/duckdb.metabase-driver.jar

EXPOSE 3000

USER metabase

CMD ["java", "-jar", "/home/metabase/metabase.jar"]
