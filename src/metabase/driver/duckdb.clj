(ns metabase.driver.duckdb
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [java-time.api :as t]
   [medley.core :as m]
   [metabase.driver :as driver]
   [metabase.driver.sql-jdbc.common :as sql-jdbc.common]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.util.honey-sql-2 :as h2x]
   [metabase.util.log :as log])
  (:import
   (java.sql
    Connection
    PreparedStatement
    ResultSet
    ResultSetMetaData
    Statement
    Time
    Types)
   (java.time LocalDate LocalTime OffsetTime)
   (java.time.temporal ChronoField)))

(set! *warn-on-reflection* true)

(defn- split-composite-schema
  "Splits a composite schema 'catalog.schema' into [catalog schema].
   If there's no dot, returns [nil schema] for backwards compatibility."
  [composite-schema]
  (if (and composite-schema (str/includes? composite-schema "."))
    (let [idx (str/index-of composite-schema ".")]
      [(subs composite-schema 0 idx) (subs composite-schema (inc idx))])
    [nil composite-schema]))

(driver/register! :duckdb, :parent :sql-jdbc)

(doseq [[feature supported?] {:metadata/key-constraints      false  ;; fetching metadata about foreign key constraints is not supported, but JOINs generally are.
                              :upload-with-auto-pk           false
                              :datetime-diff                 true}]
  (defmethod driver/database-supports? [:duckdb feature] [_driver _feature _db] supported?))

(defmethod sql-jdbc.conn/data-source-name :duckdb
  [_driver details]
  ((some-fn :database_file)
   details))

(def premium-features-namespace
  (try
    (require '[metabase.premium-features.core :as premium-features])    ;; For Metabase 0.52 or after
    'metabase.premium-features.core
    (catch Exception _
      (try
        (require '[metabase.public-settings.premium-features :as premium-features])   ;; For Metabase < 0.52
        'metabase.public-settings.premium-features
        (catch Exception e
          (throw (ex-info "Could not load either premium features namespace"
                          {:error e})))))))

(defn- is-hosted?  []
  (let [premium-feature-ns (find-ns premium-features-namespace)]
    ((ns-resolve premium-feature-ns 'is-hosted?))))

(defn- get-motherduck-token [details-map]
  (try
    ;; For Metabase 0.55 or after
    ((requiring-resolve 'metabase.secrets.models.secret/value-as-string) :duckdb details-map "motherduck_token")
    (catch Exception _
      (try
        ;; For Metabase < 0.55
        ((requiring-resolve 'metabase.models.secret/value-as-string) :duckdb details-map "motherduck_token")
        (catch Exception _
          ;; For Metabase < 0.52
          (or (-> ((requiring-resolve 'metabase.models.secret/db-details-prop->secret-map) details-map "motherduck_token")
                  ((requiring-resolve 'metabase.models.secret/value->string)))
              ((requiring-resolve 'metabase.models.secret/get-secret-string) details-map "motherduck_token")))))))

(defn- database-file-path-split [database_file]
  (let [url-parts (str/split database_file #"\?")]
    (if (= 2 (count url-parts))
      (let [database-file (first url-parts)
            additional-options (second url-parts)]
        [database-file additional-options])
      [database_file ""])))

(defn- jdbc-spec
  "Creates a spec for `clojure.java.jdbc` to use for connecting to DuckDB via JDBC from the given `opts`"
  [{:keys [database_file, read_only, allow_unsigned_extensions, old_implicit_casting,
           motherduck_token, memory_limit, azure_transport_option_type, attach_mode, additional-options]}]
  (let [[database_file_base database_file_additional_options] (database-file-path-split database_file)]
    (merge
     {:classname         "org.duckdb.DuckDBDriver"
      :subprotocol       "duckdb"
      :subname           (or database_file "")
      "duckdb.read_only" (str read_only)
      "custom_user_agent" (str "metabase" (if (is-hosted?) " metabase-cloud" ""))
      "temp_directory"   (str database_file_base ".tmp")
      "jdbc_stream_results" "true"
      :TimeZone  "UTC"}
     (when old_implicit_casting
       {"old_implicit_casting" (str old_implicit_casting)})
     (when memory_limit
       {"memory_limit" (str memory_limit)})
     (when azure_transport_option_type
       {"azure_transport_option_type" (str azure_transport_option_type)})
     (when allow_unsigned_extensions
       {"allow_unsigned_extensions" (str allow_unsigned_extensions)})
     (when (seq (re-find #"^md:" database_file))
       ;; attach_mode option is not settable by the user, it's always single mode when
       ;; using motherduck, but in tests we need to be able to connect to motherduck in
       ;; workspace mode, so it's handled here.
       {"motherduck_attach_mode"  (or attach_mode "single")})    ;; when connecting to MotherDuck, explicitly connect to a single database
     (when (seq motherduck_token)     ;; Only configure the option if token is provided
       {"motherduck_token" motherduck_token})
     (sql-jdbc.common/additional-options->map additional-options :url)
     (sql-jdbc.common/additional-options->map database_file_additional_options :url))))

(defn- remove-keys-with-prefix [details prefix]
  (apply dissoc details (filter #(str/starts-with? (name %) prefix) (keys details))))

(defmethod sql-jdbc.conn/connection-details->spec :duckdb
  [_ details-map]
  (-> details-map
      (merge {:motherduck_token (get-motherduck-token details-map)})
      (remove-keys-with-prefix "motherduck_token-")
      jdbc-spec))

(defmethod sql-jdbc.execute/do-with-connection-with-options :duckdb
  [driver db-or-id-or-spec {:keys [^String session-timezone report-timezone] :as options} f]
  ;; First use the parent implementation to get the connection with standard options
  (sql-jdbc.execute/do-with-resolved-connection
   driver
   db-or-id-or-spec
   options
   (fn [^Connection conn]
     ;; Run init SQL on every connection checkout (not just once globally) because
     ;; connection pools have multiple connections and settings like search_path
     ;; are connection-specific. Operations like INSTALL/LOAD/SET are idempotent.
     (when (not (sql-jdbc.execute/recursive-connection?))
       (when-let [init-sql (-> db-or-id-or-spec :details :init_sql)]
         (when (seq (str/trim init-sql))
           (try
             (with-open [stmt (.createStatement conn)]
               (.execute stmt init-sql)
               (log/tracef "Successfully executed DuckDB init SQL"))
             (catch Throwable e
               (log/errorf e "Failed to execute DuckDB init SQL"))))))
     ;; Additionally set timezone if provided and we're not in a recursive connection
     (when (and (or report-timezone session-timezone) (not (sql-jdbc.execute/recursive-connection?)))
       (let [timezone-to-use (or report-timezone session-timezone)]
         (try
           (with-open [stmt (.createStatement conn)]
             (.execute stmt (format "SET TimeZone='%s';" timezone-to-use)))
           (catch Throwable e
             (log/debugf e "Error setting timezone '%s' for DuckDB database" timezone-to-use)))))
     ;; Call the function with the configured connection
     (f conn))))

(defmethod sql-jdbc.execute/set-timezone-sql :duckdb [_]
  "SET GLOBAL TimeZone=%s;")

(def ^:private database-type->base-type
  (sql-jdbc.sync/pattern-based-database-type->base-type
   [[#"BOOLEAN"                  :type/Boolean]
    [#"BOOL"                     :type/Boolean]
    [#"LOGICAL"                  :type/Boolean]
    [#"HUGEINT"                  :type/BigInteger]
    [#"UBIGINT"                  :type/BigInteger]
    [#"BIGINT"                   :type/BigInteger]
    [#"INT8"                     :type/BigInteger]
    [#"LONG"                     :type/BigInteger]
    [#"INT4"                     :type/Integer]
    [#"SIGNED"                   :type/Integer]
    [#"INT2"                     :type/Integer]
    [#"SHORT"                    :type/Integer]
    [#"INT1"                     :type/Integer]
    [#"UINTEGER"                 :type/Integer]
    [#"USMALLINT"                :type/Integer]
    [#"UTINYINT"                 :type/Integer]
    [#"INTEGER"                  :type/Integer]
    [#"SMALLINT"                 :type/Integer]
    [#"TINYINT"                  :type/Integer]
    [#"INT"                      :type/Integer]
    [#"DECIMAL"                  :type/Decimal]
    [#"DOUBLE"                   :type/Float]
    [#"FLOAT8"                   :type/Float]
    [#"NUMERIC"                  :type/Float]
    [#"REAL"                     :type/Float]
    [#"FLOAT4"                   :type/Float]
    [#"FLOAT"                    :type/Float]
    [#"VARCHAR"                  :type/Text]
    [#"BPCHAR"                   :type/Text]
    [#"CHAR"                     :type/Text]
    [#"TEXT"                     :type/Text]
    [#"STRING"                   :type/Text]
    [#"JSON"                     :type/JSON]
    [#"BLOB"                     :type/*]
    [#"BYTEA"                    :type/*]
    [#"VARBINARY"                :type/*]
    [#"BINARY"                   :type/*]
    [#"UUID"                     :type/UUID]
    [#"TIMESTAMPTZ"              :type/DateTimeWithTZ]
    [#"TIMESTAMP WITH TIME ZONE" :type/DateTimeWithTZ]
    [#"DATETIME"                 :type/DateTime]
    [#"TIMESTAMP_S"              :type/DateTime]
    [#"TIMESTAMP_MS"             :type/DateTime]
    [#"TIMESTAMP_NS"             :type/DateTime]
    [#"TIMESTAMP"                :type/DateTime]
    [#"DATE"                     :type/Date]
    [#"TIME"                     :type/Time]
    [#"GEOMETRY"                 :type/*]]))

(defmethod sql-jdbc.sync/database-type->base-type :duckdb
  [_ field-type]
  (database-type->base-type field-type))

(defn- local-time-to-time [^LocalTime lt]
  (Time. (.getLong lt ChronoField/MILLI_OF_DAY)))

(defmethod sql-jdbc.execute/set-parameter [:duckdb LocalDate]
  [_ ^PreparedStatement prepared-statement i t]
  (.setObject prepared-statement i (t/local-date-time t (t/local-time 0))))

(defmethod sql-jdbc.execute/set-parameter [:duckdb LocalTime]
  [_ ^PreparedStatement prepared-statement i t]
  (.setObject prepared-statement i (local-time-to-time t)))

(defmethod sql-jdbc.execute/set-parameter [:duckdb OffsetTime]
  [_ ^PreparedStatement prepared-statement i ^OffsetTime t]
  (let [adjusted-tz  (local-time-to-time (t/local-time (t/with-offset-same-instant t (t/zone-offset 0))))]
    (.setObject prepared-statement i adjusted-tz)))

(defmethod sql-jdbc.execute/set-parameter [:duckdb String]
  [_ ^PreparedStatement prepared-statement i t]
  (.setObject prepared-statement i t))

;; .getObject of DuckDB (v0.4.0) does't handle the java.time.LocalDate but sql.Date only,
;; so get the sql.Date from DuckDB and convert it to java.time.LocalDate
(defmethod sql-jdbc.execute/read-column-thunk [:duckdb Types/DATE]
  [_ ^ResultSet rs _rsmeta ^Integer i]
  (fn []
    (when-let [sqlDate (.getDate rs i)]
      (.toLocalDate sqlDate))))

;; .getObject of DuckDB (v0.4.0) does't handle the java.time.LocalTime but sql.Time only,
;; so get the sql.Time from DuckDB and convert it to java.time.LocalTime
(defmethod sql-jdbc.execute/read-column-thunk [:duckdb Types/TIME]
  [_ ^ResultSet rs _rsmeta ^Integer i]
  (fn []
    (when-let [sql-time-string (.getString rs i)]
      (LocalTime/parse sql-time-string))))

;; override the sql-jdbc.execute/read-column-thunk for TIMESTAMP based on
;; DuckDB JDBC implementation.
(defmethod sql-jdbc.execute/read-column-thunk [:duckdb Types/TIMESTAMP]
  [_ ^ResultSet rs _ ^Integer i]
  (fn []
    (when-let [t (.getTimestamp rs i)]
      (t/local-date-time t))))

;; date processing for aggregation
(defmethod driver/db-start-of-week :duckdb [_] :monday)

(defmethod sql.qp/add-interval-honeysql-form :duckdb
  [driver hsql-form amount unit]
  (if (= unit :quarter)
    (recur driver hsql-form (* amount 3) :month)
    (h2x/+ (h2x/->timestamp-with-time-zone hsql-form) [:raw (format "(INTERVAL '%d' %s)" (int amount) (name unit))])))

(defmethod sql.qp/date [:duckdb :default]         [_ _ expr] expr)
(defmethod sql.qp/date [:duckdb :minute]          [_ _ expr] [:date_trunc (h2x/literal :minute) expr])
(defmethod sql.qp/date [:duckdb :minute-of-hour]  [_ _ expr] [:minute expr])
(defmethod sql.qp/date [:duckdb :hour]            [_ _ expr] [:date_trunc (h2x/literal :hour) expr])
(defmethod sql.qp/date [:duckdb :hour-of-day]     [_ _ expr] [:hour expr])
(defmethod sql.qp/date [:duckdb :day]             [_ _ expr] [:date_trunc (h2x/literal :day) expr])
(defmethod sql.qp/date [:duckdb :day-of-month]    [_ _ expr] [:day expr])
(defmethod sql.qp/date [:duckdb :day-of-year]     [_ _ expr] [:dayofyear expr])

(defmethod sql.qp/date [:duckdb :day-of-week]
  [driver _ expr]
  (sql.qp/adjust-day-of-week driver [:isodow expr]))

(defmethod sql.qp/date [:duckdb :week]
  [driver _ expr]
  (sql.qp/adjust-start-of-week driver (partial conj [:date_trunc] (h2x/literal :week)) expr))

(defmethod sql.qp/date [:duckdb :month]           [_ _ expr] [:date_trunc (h2x/literal :month) expr])
(defmethod sql.qp/date [:duckdb :month-of-year]   [_ _ expr] [:month expr])
(defmethod sql.qp/date [:duckdb :quarter]         [_ _ expr] [:date_trunc (h2x/literal :quarter) expr])
(defmethod sql.qp/date [:duckdb :quarter-of-year] [_ _ expr] [:quarter expr])
(defmethod sql.qp/date [:duckdb :year]            [_ _ expr] [:date_trunc (h2x/literal :year) expr])

(defmethod sql.qp/datetime-diff [:duckdb :year]
  [_driver _unit x y]
  [:datesub (h2x/literal :year) (h2x/cast "date" x) (h2x/cast "date" y)])

(defmethod sql.qp/datetime-diff [:duckdb :quarter]
  [_driver _unit x y]
  [:datesub (h2x/literal :quarter) (h2x/cast "date" x) (h2x/cast "date" y)])

(defmethod sql.qp/datetime-diff [:duckdb :month]
  [_driver _unit x y]
  [:datesub (h2x/literal :month) (h2x/cast "date" x) (h2x/cast "date" y)])

(defmethod sql.qp/datetime-diff [:duckdb :week]
  [_driver _unit x y]
  (h2x// [:datesub (h2x/literal :day) (h2x/cast "date" x) (h2x/cast "date" y)] 7))

(defmethod sql.qp/datetime-diff [:duckdb :day]
  [_driver _unit x y]
  [:datesub (h2x/literal :day) (h2x/cast "date" x) (h2x/cast "date" y)])

(defmethod sql.qp/datetime-diff [:duckdb :hour]
  [_driver _unit x y]
  [:datesub (h2x/literal :hour) x y])

(defmethod sql.qp/datetime-diff [:duckdb :minute]
  [_driver _unit x y]
  [:datesub (h2x/literal :minute) x y])

(defmethod sql.qp/datetime-diff [:duckdb :second]
  [_driver _unit x y]
  [:datesub (h2x/literal :second) x y])

(defmethod sql.qp/unix-timestamp->honeysql [:duckdb :seconds]
  [_ _ expr]
  [:to_timestamp (h2x/cast :DOUBLE expr)])

(defmethod sql.qp/->honeysql [:duckdb :regex-match-first]
  [driver [_ arg pattern]]
  [:regexp_extract (sql.qp/->honeysql driver arg) (sql.qp/->honeysql driver pattern)])

;; Override table identifier generation to handle composite "catalog.schema" schemas
;; This produces three-part identifiers: catalog.schema.table
(defmethod sql.qp/->honeysql [:duckdb :metadata/table]
  [driver {:keys [schema name] :as table}]
  (log/tracef "DuckDB :metadata/table method called! schema=%s name=%s" schema name)
  (let [[catalog actual-schema] (split-composite-schema schema)]
    (if catalog
      ;; Three-part identifier: catalog.schema.table
      (h2x/identifier :table catalog actual-schema name)
      ;; Fall back to default behavior for non-composite schemas
      ((get-method sql.qp/->honeysql [:sql :metadata/table]) driver table))))

;; Helper function to check if an expression is an h2x identifier
(defn- h2x-identifier?
  "Check if expr is a HoneySQL 2 identifier vector of the form [::h2x/identifier type components]"
  [expr]
  (and (vector? expr)
       (>= (count expr) 3)
       (= (first expr) :metabase.util.honey-sql-2/identifier)))

;; Helper function to split composite schemas in h2x/identifier expressions
(defn- split-identifier-components
  "Given identifier components, split the first one if it contains a dot."
  [identifier-type components]
  (if (and (vector? components)
           (seq components)
           (string? (first components))
           (str/includes? (first components) ".")
           ;; Split for both :table and :field identifiers that have schema component
           (or (and (= identifier-type :table)
                    (>= (count components) 2))
               (and (= identifier-type :field)
                    (>= (count components) 3))))
    (let [[catalog schema] (split-composite-schema (first components))]
      (log/tracef "Splitting composite schema: %s -> [%s %s]" (first components) catalog schema)
      (vec (concat [catalog schema] (rest components))))
    components))

(defn- split-identifier-schema
  "If expr is an h2x/identifier (possibly wrapped in ::typed) with a composite schema
   (contains dot) in the first component, split it into separate catalog and schema parts."
  [expr]
  (cond
    ;; Handle bare identifier: [::h2x/identifier type components]
    (h2x-identifier? expr)
    (let [[tag identifier-type components] expr
          new-components (split-identifier-components identifier-type components)]
      (if (= components new-components)
        expr
        (do
          (log/tracef "Split identifier: %s -> %s" (pr-str expr) (pr-str [tag identifier-type new-components]))
          [tag identifier-type new-components])))

    ;; Handle typed expression: [::h2x/typed inner-expr type-info]
    (and (vector? expr)
         (= (first expr) :metabase.util.honey-sql-2/typed))
    (let [[typed-tag inner-expr type-info] expr
          processed-inner (split-identifier-schema inner-expr)]
      (if (= inner-expr processed-inner)
        expr
        [typed-tag processed-inner type-info]))

    ;; Return as-is for anything else
    :else expr))

;; Override :field to post-process identifiers and split composite schemas
(defmethod sql.qp/->honeysql [:duckdb :field]
  [driver field-clause]
  (let [parent-method (get-method sql.qp/->honeysql [:sql :field])
        result (parent-method driver field-clause)
        processed (split-identifier-schema result)]
    (when (not= result processed)
      (log/tracef "DuckDB :field split result: %s -> %s" (pr-str result) (pr-str processed)))
    processed))

;; empty result set for queries without result (like insert...)
(defn- empty-rs []
  (reify
    ResultSet
    (getMetaData [_]
      (reify
        ResultSetMetaData
        (getColumnCount [_] 1)
        (getColumnLabel [_ _idx] "WARNING")
        (getColumnTypeName [_ _] "CHAR")
        (getColumnType [_ _] Types/CHAR)))
    (next [_] false)
    (close [_])))

;; override native execute-statement! to make queries that does't returns ResultSet

(defmethod sql-jdbc.execute/execute-statement! :duckdb
  [_driver ^Statement stmt ^String sql]
  (if (.execute stmt sql)
    (.getResultSet stmt)
    (empty-rs)))

(defn- is_motherduck
  [database_file]
  (and (seq (re-find #"^md:" database_file)) (> (count database_file) 3)))

(defn- motherduck_db_name
  [database_file]
  (subs database_file 3))

;; Creates a new connection to the same DuckDB instance to avoid deadlocks during concurrent operations.
;; context: observed in tests that sometimes multiple syncs can be triggered on the same db at the same time,
;; (and potentially the deletion of the local duckdb file) that results in bad_weak_ptr errors on the duckdb
;; connection object and deadlocks, so creating a lightweight clone of the connection to the same duckdb
;; instance to avoid deadlocks.
(defn- clone-raw-connection [connection database]
  (let [c3p0-conn (cast com.mchange.v2.c3p0.C3P0ProxyConnection connection)
        clone-method (.getMethod org.duckdb.DuckDBConnection "duplicate" (into-array Class []))
        raw-conn-token com.mchange.v2.c3p0.C3P0ProxyConnection/RAW_CONNECTION
        args (into-array Object [])
        ^Connection cloned-conn (.rawConnectionOperation c3p0-conn clone-method raw-conn-token args)]
    ;; Run init SQL on the cloned connection so that search_path and other settings are applied
    (when-let [init-sql (-> database :details :init_sql)]
      (when (seq (str/trim init-sql))
        (try
          (with-open [stmt (.createStatement cloned-conn)]
            (.execute stmt init-sql))
          (catch Throwable e
            (log/errorf e "Failed to execute init SQL on cloned connection")))))
    cloned-conn))

(def ^:private search-path-filter
  "SQL filter clause that restricts results to schemas in the search_path.
   IMPORTANT: This entire clause must be wrapped in outer parentheses so that
   the internal OR conditions don't break out of the AND chain in WHERE clauses."
  (str "((current_setting('search_path') IS NULL "
         "OR current_setting('search_path') = '' "
         "AND table_catalog = current_database() "
         "AND table_schema = current_schema()) "
       "OR (table_catalog || '.' || table_schema) IN ("
       "SELECT unnest(string_split(current_setting('search_path'), ','))"
       "))"))

(def ^:private excluded-schemas-filter
  "SQL filter clause that excludes system schemas when not filtering by search_path."
  (str "table_schema NOT IN ('information_schema', 'pg_catalog', 'md_information_schema') "
       "AND table_catalog NOT IN ('system', 'temp')"))

(defn- schema-filter-for-database
  "Returns the appropriate schema filter SQL clause based on database settings.
   When filter_by_search_path is true, uses search_path filter.
   When false (default), excludes only system schemas."
  [database]
  (if (get-in database [:details :filter_by_search_path])
    search-path-filter
    excluded-schemas-filter))

(defmethod driver/describe-database :duckdb
  [driver database]
  (let [schema-filter (schema-filter-for-database database)
        get_tables_query (str "SELECT table_catalog || '.' || table_schema AS table_schema, table_name "
                              "FROM information_schema.tables WHERE table_catalog NOT LIKE '__ducklake_metadata%' AND "
                              schema-filter)]
    {:tables
     (sql-jdbc.execute/do-with-connection-with-options
      driver database nil
      (fn [conn]
        (set
         (for [{:keys [table_schema table_name]}
               (jdbc/query {:connection (clone-raw-connection conn database)}
                           [get_tables_query])]
           {:name table_name :schema table_schema}))))}))

(defmethod driver/describe-table :duckdb
  [driver database {table_name :name, schema :schema}]
  (let [[catalog actual-schema] (split-composite-schema schema)
        catalog-filter (if catalog
                         (format "table_catalog = '%s' AND " catalog)
                         "")
        schema-filter (schema-filter-for-database database)
        get_columns_query (str
                           (format
                            "SELECT * FROM information_schema.columns WHERE table_name = '%s' AND %stable_schema = '%s' AND table_catalog NOT LIKE '__ducklake_metadata%%' AND "
                            table_name catalog-filter actual-schema)
                           schema-filter)]
    {:name   table_name
     :schema schema
     :fields
     (sql-jdbc.execute/do-with-connection-with-options
      driver database nil
      (fn [conn] (let [results (jdbc/query
                                {:connection (clone-raw-connection conn database)}
                                [get_columns_query])]
                   (set
                    (for [[idx {column_name :column_name, data_type :data_type}] (m/indexed results)]
                      {:name              column_name
                       :database-type     data_type
                       :base-type         (sql-jdbc.sync/database-type->base-type driver (keyword data_type))
                       :database-position idx})))))}))

;; The 0.4.0 DuckDB JDBC .getImportedKeys method throws 'not implemented' yet.
;; There is no support of FK yet.
(defmethod driver/describe-table-fks :duckdb
  [_ _ _]
  nil)
