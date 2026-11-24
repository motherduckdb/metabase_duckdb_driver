(ns metabase.driver.duckdb-test
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [clojure.test :refer [are deftest is testing]]
   [metabase.driver :as driver]
   [metabase.driver.duckdb]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.query-processor :as qp]
   [metabase.sync.core :as sync]
   [metabase.test :as mt]
   [metabase.util.honey-sql-2 :as h2x]
   [toucan2.core :as t2]))

(deftest motherduck-spec-gets-its-own-instance-test
  (testing "a MotherDuck spec's cache key is stable for the same details, so Metabase's spec hash does not invalidate
           the pool on every checkout, and changes with the details, so changed details open a fresh instance"
    (let [spec #(sql-jdbc.conn/connection-details->spec :duckdb %)
          a    (spec {:database_file "md:my_db" :motherduck_token-value "one"})
          same (spec {:database_file "md:my_db" :motherduck_token-value "one"})
          diff (spec {:database_file "md:my_db" :motherduck_token-value "two"})]
      (is (= (:subname a) (:subname same)))
      (is (= (hash a) (hash same)))
      (is (not= (:subname a) (:subname diff)))))
  (testing "options already on the path are kept"
    (is (re-find #"^md:my_db\?read_only=true&cache_bust="
                 (:subname (sql-jdbc.conn/connection-details->spec :duckdb {:database_file "md:my_db?read_only=true"})))))
  (testing "local files are keyed by their path and get neither"
    (let [spec (sql-jdbc.conn/connection-details->spec :duckdb {:database_file "/data/warehouse.db"})]
      (is (= "/data/warehouse.db" (:subname spec)))
      (is (nil? (get spec "motherduck_dbinstance_inactivity_ttl"))))))

(deftest can-connect-recycles-conflicting-pool-test
  (testing "validating changed details recycles the live pool instead of failing (token rotation flow):
           DuckDB refuses to open the same file with a different configuration while the old
           instance has open connections, which used to fail validation and so block the save"
    (let [file (str (System/getProperty "java.io.tmpdir") "/pool-recycle-" (System/currentTimeMillis) ".db")]
      (mt/with-temp [:model/Database db {:engine :duckdb, :details {:database_file file}}]
        (try
          ;; open a pooled connection the way a running Metabase holds one, then sync: describe-database and
          ;; describe-table clone raw connections outside the pool, which used to leak and keep the instance alive
          (jdbc/execute! (sql-jdbc.conn/db->pooled-connection-spec db) ["CREATE TABLE t (i INTEGER)"])
          (driver/describe-database :duckdb db)
          (driver/describe-table :duckdb db {:name "t", :schema "main"})
          ;; same file, different config: without recycling this throws
          ;; "Can't open a connection to same database file with a different configuration"
          (is (true? (driver/can-connect? :duckdb {:database_file file, :read_only true})))
          (finally
            (sql-jdbc.conn/invalidate-pool-for-db! db)))))))

(defn- do-with-quack-server
  "Calls (f uri) while a quack server listens on uri, a free local port, with token test-token. It serves a table
   events holding the row (1, 'a') and a table named it's."
  [f]
  (let [uri    (str "quack:localhost:" (with-open [socket (java.net.ServerSocket. 0)] (.getLocalPort socket)))
        server (str (System/getProperty "java.io.tmpdir") "/quack-server-" (System/currentTimeMillis) ".db")]
    ;; the server runs inside this connection's DuckDB instance for as long as the connection stays open
    (with-open [conn (java.sql.DriverManager/getConnection (str "jdbc:duckdb:" server))]
      (jdbc/execute! {:connection conn} [(str "INSTALL quack; LOAD quack; "
                                              "CREATE TABLE events AS SELECT 1 AS id, 'a' AS name; "
                                              "CREATE TABLE \"it's\" (n INTEGER)")])
      (jdbc/query {:connection conn} [(format "FROM quack_serve('%s', token := 'test-token')" uri)])
      (f uri))))

(defn- check-quack-sync-and-query
  "Asserts that db syncs the served tables under schema and that a query on the synced events table returns its row."
  [db schema]
  (testing "tables and columns are fetched from the server, since DuckDB lists nothing for a quack catalog in
           information_schema"
    (is (contains? (:tables (driver/describe-database :duckdb db))
                   {:name "events", :schema schema, :description nil}))
    (is (= [{:name "id", :database-type "INTEGER", :base-type :type/Integer}
            {:name "name", :database-type "VARCHAR", :base-type :type/Text}]
           (->> (driver/describe-table :duckdb db {:name "events", :schema schema})
                :fields
                (sort-by :database-position)
                (map #(select-keys % [:name :database-type :base-type]))))))
  (testing "a quote in a table name is escaped in the SQL sent to the server"
    (is (= ["n"] (map :name (:fields (driver/describe-table :duckdb db {:name "it's", :schema schema}))))))
  (testing "a synced table can be queried"
    (sync/sync-database! db {:scan :schema})
    (is (= [[1 "a"]]
           (mt/rows (qp/process-query
                     {:database (:id db)
                      :type     :query
                      :query    {:source-table (t2/select-one-pk :model/Table :db_id (:id db) :name "events")}}))))))

(deftest quack-database-file-test
  (testing "the server URI as the database file, with the token in a persistent secret"
    (do-with-quack-server
     (fn [uri]
       (with-open [conn (java.sql.DriverManager/getConnection "jdbc:duckdb:")]
         (jdbc/query {:connection conn}
                     [(format (str "CREATE OR REPLACE PERSISTENT SECRET quack_test "
                                   "(TYPE quack, TOKEN 'test-token', SCOPE '%s')")
                              uri)])
         (try
           (mt/with-temp [:model/Database db {:engine :duckdb, :details {:database_file uri}}]
             (try
               (check-quack-sync-and-query db uri)
               (finally
                 (sql-jdbc.conn/invalidate-pool-for-db! db))))
           (finally
             (jdbc/execute! {:connection conn} ["DROP PERSISTENT SECRET quack_test"]))))))))

(deftest quack-attached-in-init-sql-test
  (testing "the server attached from Init SQL to an ordinary database file"
    (do-with-quack-server
     (fn [uri]
       (let [client (str (System/getProperty "java.io.tmpdir") "/quack-client-" (System/currentTimeMillis) ".db")]
         (mt/with-temp [:model/Database db {:engine  :duckdb
                                            :details {:database_file client
                                                      :init_sql      (format (str "INSTALL quack; LOAD quack; "
                                                                                  "ATTACH IF NOT EXISTS '%s' AS remote "
                                                                                  "(TOKEN 'test-token');")
                                                                             uri)}}]
           (try
             (check-quack-sync-and-query db "quack:remote")
             (finally
               (sql-jdbc.conn/invalidate-pool-for-db! db)))))))))

(deftest ^:parallel quack-identifier-test
  (testing "a table synced from a quack catalog is addressed as catalog.table and its columns are qualified by the
           table alone, because a quack catalog binds only the bare table name"
    (are [identifier expected] (= expected (sql.qp/->honeysql :duckdb identifier))
      (h2x/identifier :table "quack:remote" "events")      (h2x/identifier :table "remote" "events")
      (h2x/identifier :field "quack:remote" "events" "id") (h2x/identifier :field "events" "id")
      (h2x/identifier :field "main" "events" "id")         (h2x/identifier :field "main" "events" "id"))))

(defn- temp-dir-for [database-file]
  (get (sql-jdbc.conn/connection-details->spec :duckdb {:database_file database-file})
       "temp_directory"))

(deftest temp-directory-test
  (testing "a plain database file spills next to itself"
    (is (= "/data/warehouse.db.tmp" (temp-dir-for "/data/warehouse.db")))
    (is (= "warehouse.db.tmp" (temp-dir-for "warehouse.db"))))
  (testing "query parameters are not part of the temp directory"
    (is (= "/data/warehouse.db.tmp" (temp-dir-for "/data/warehouse.db?read_only=true"))))
  (testing "URL-style paths and :memory: spill under java.io.tmpdir, never a path with a scheme"
    (let [tmpdir (System/getProperty "java.io.tmpdir")]
      (doseq [database-file ["ducklake:/lake/catalog.ducklake"
                             "ducklake:sqlite:/warehouse/meta.db"
                             "md:my_database"
                             "md:my_database?motherduck_token=x"
                             ":memory:"]]
        (let [temp-dir (temp-dir-for database-file)]
          (is (str/starts-with? temp-dir tmpdir) database-file)
          (is (not (str/includes? (subs temp-dir (count tmpdir)) ":")) database-file)))))
  (testing "different databases never share a spill directory"
    (is (not= (temp-dir-for "md:db_one") (temp-dir-for "md:db_two")))))

(deftest connection-spec-omits-timezone-test
  (testing "TimeZone is not a startup property: it comes from the icu extension, which cannot
           be autoloaded on air-gapped installs, and there it fails the whole connection"
    (let [spec (sql-jdbc.conn/connection-details->spec :duckdb {:database_file ":memory:"})]
      (is (not-any? #{:TimeZone "TimeZone"} (keys spec))))))

(deftest ^:parallel database-type->base-type-test
  (testing "nested types are typed as a whole, not by the scalar type names inside them"
    (are [database-type base-type] (= base-type (sql-jdbc.sync/database-type->base-type :duckdb database-type))
      "STRUCT(started_at TIMESTAMP, ended_at TIMESTAMP)" :type/Dictionary
      "STRUCT(plan VARCHAR, seats INTEGER)"              :type/Dictionary
      "MAP(VARCHAR, INTEGER)"                            :type/Dictionary
      "INTEGER[]"                                        :type/Array
      "INTEGER[3]"                                       :type/Array
      "STRUCT(a DATE)[]"                                 :type/Array
      "UNION(num INTEGER, str VARCHAR)"                  :type/*))
  (testing "names that contain a scalar type name, or had no pattern, are mapped explicitly"
    (are [database-type base-type] (= base-type (sql-jdbc.sync/database-type->base-type :duckdb database-type))
      "INTERVAL"                      :type/*
      "ENUM('sad', 'ok', 'INTERNAL')" :type/Text
      "ENUM('it''s', 'a)b', 'DATE', 'STRUCT(')" :type/Text
      "BIGNUM"                        :type/BigInteger
      "POINT_2D"                      :type/*
      "LINESTRING_2D"                 :type/*
      "TIME WITH TIME ZONE"           :type/TimeWithTZ))
  (testing "scalar types keep their mapping"
    (are [database-type base-type] (= base-type (sql-jdbc.sync/database-type->base-type :duckdb database-type))
      "INTEGER"                  :type/Integer
      "DECIMAL(18,3)"            :type/Decimal
      "TIMESTAMP WITH TIME ZONE" :type/DateTimeWithTZ
      "VARCHAR"                  :type/Text)))
