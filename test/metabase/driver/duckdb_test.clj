(ns metabase.driver.duckdb-test
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.driver.duckdb]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.test :as mt]))

(comment metabase.driver.duckdb/keep-me)

(deftest can-connect-recycles-conflicting-pool-test
  (testing "validating changed details recycles the live pool instead of failing (token rotation flow):
           DuckDB refuses to open the same file with a different configuration while the old
           instance has open connections, which used to fail validation and so block the save"
    (let [file (str (System/getProperty "java.io.tmpdir") "/pool-recycle-" (System/currentTimeMillis) ".db")]
      (mt/with-temp [:model/Database db {:engine :duckdb, :details {:database_file file}}]
        (try
          ;; open a pooled connection the way a running Metabase holds one
          (jdbc/query (sql-jdbc.conn/db->pooled-connection-spec db) ["SELECT 1"])
          ;; same file, different config: without recycling this throws
          ;; "Can't open a connection to same database file with a different configuration"
          (is (true? (driver/can-connect? :duckdb {:database_file file, :read_only true})))
          (finally
            (sql-jdbc.conn/invalidate-pool-for-db! db)))))))
