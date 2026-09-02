(ns metabase.driver.duckdb-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver.duckdb]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]))

(comment metabase.driver.duckdb/keep-me)

(deftest connection-spec-omits-timezone-test
  (testing "TimeZone is not a startup property: it comes from the icu extension, which cannot
           be autoloaded on air-gapped installs, and there it fails the whole connection"
    (let [spec (sql-jdbc.conn/connection-details->spec :duckdb {:database_file ":memory:"})]
      (is (not-any? #{:TimeZone "TimeZone"} (keys spec))))))
