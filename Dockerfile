FROM eclipse-temurin:21-jre-jammy

ENV MB_PLUGINS_DIR=/home/metabase/plugins/

RUN groupadd -r metabase && useradd -r -g metabase metabase

RUN apt-get update && apt-get install -y \
    ca-certificates curl jq \
    && rm -rf /var/lib/apt/lists/*

# /home/metabase/plugins will hold the plugins
# /home/metabase/data is not strictly necessary, can be a place to store data files (.duckdb, .parquet, etc)
RUN mkdir -p /home/metabase/plugins /home/metabase/data && \
    chown -R metabase:metabase /home/metabase

WORKDIR /home/metabase

# Build arguments declared here so base layers above are always cached.
# METABASE_VERSION is empty by default and falls back to the newest version
# listed in metabase_versions.json, so a plain `docker build .` builds the
# latest supported combination.
ARG METABASE_VERSION

COPY metabase_versions.json /tmp/
RUN mb="${METABASE_VERSION:-$(jq -r 'sort_by(split(".") | map(tonumber)) | last' /tmp/metabase_versions.json)}" && \
    curl -fsSL -o metabase.jar "https://downloads.metabase.com/v${mb}/metabase.jar" && \
    chown metabase:metabase metabase.jar && \
    chmod 755 metabase.jar

# Where the driver jar comes from: the newest release by default; CI passes the
# exact release URL of the version it is building (which also points forks at
# their own releases). Point it at a jar in the build context to run a local
# build instead, e.g.
#   docker build --build-arg DUCKDB_DRIVER_URL=dist/duckdb.metabase-driver.jar .
# Declared after the RUN above so changing it does not re-download metabase.jar.
ARG DUCKDB_DRIVER_URL=https://github.com/motherduckdb/metabase_duckdb_driver/releases/latest/download/duckdb.metabase-driver.jar
ADD --chown=metabase:metabase --chmod=755 ${DUCKDB_DRIVER_URL} /home/metabase/plugins/duckdb.metabase-driver.jar

EXPOSE 3000

USER metabase

CMD ["java", "-jar", "/home/metabase/metabase.jar"]
