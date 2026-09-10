(ns metabase.driver.duckdb-test
  (:require
   [clojure.test :refer [are deftest testing]]
   [metabase.driver.duckdb]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]))

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
  (testing "scalar types keep their mapping"
    (are [database-type base-type] (= base-type (sql-jdbc.sync/database-type->base-type :duckdb database-type))
      "INTEGER"                  :type/Integer
      "DECIMAL(18,3)"            :type/Decimal
      "TIMESTAMP WITH TIME ZONE" :type/DateTimeWithTZ
      "VARCHAR"                  :type/Text)))
