FROM eclipse-temurin:21-jre-jammy

# Build arguments for versions
ARG METABASE_VERSION=0.57.6
ARG METABASE_DUCKDB_DRIVER_VERSION=1.4.3.0

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
COPY --chown=metabase:metabase duckdb.metabase-driver.jar /home/metabase/plugins/

# Ensure proper file permissions
RUN chmod 755 /home/metabase/metabase.jar && \
    chmod 755 /home/metabase/plugins/duckdb.metabase-driver.jar

EXPOSE 3000

USER metabase

CMD ["java", "-jar", "/home/metabase/metabase.jar"]