(ns metabase.driver.duckdb-test
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [clojure.test :refer [are deftest is testing]]
   [metabase.driver :as driver]
   [metabase.driver.duckdb]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.test :as mt]))

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
