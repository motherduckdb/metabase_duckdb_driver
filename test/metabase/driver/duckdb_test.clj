(ns metabase.driver.duckdb-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase.driver.duckdb]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]))

(comment metabase.driver.duckdb/keep-me)

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
