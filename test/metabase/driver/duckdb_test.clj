(ns metabase.driver.duckdb-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [are deftest is testing]]
   [metabase.driver.duckdb]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]))

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
