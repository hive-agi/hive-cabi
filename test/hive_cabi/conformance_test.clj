(ns hive-cabi.conformance-test
  "Conformance observations over both live shared libraries and stub ports."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [hive-cabi.loader :as loader]
            [hive-cabi.resolve :as resolve]
            [hive-spi.native.conformance :as conformance]
            [hive-spi.native.ports :as ports]))

(defn- fixture-spec [library dir]
  {:native/library library
   :native/abi :hive-cabi/v1
   :native/artifacts {(resolve/os-arch) {:shared (str dir "/lib" library ".so")}}})

(deftest stub-conforms-to-spi-observations
  (let [p (reify ports/INativePort
            (library [_] "stub")
            (host [_] :jvm/ffm)
            (call-op [_ op _] {:ok true :value op})
            (close! [_] nil))
        expected [{:op "ping" :request {} :expected {:ok true :value "ping"}}]]
    (is (conformance/conformant? (conformance/observations p expected)))))

(deftest live-shared-libraries-conform-to-spi
  (let [dir (System/getenv "HIVE_POLYGLOT_NATIVE")]
    (doseq [library ["autopdf" "lazywal" "photocraft" "vectorcraft"]]
      (let [path (when dir (io/file dir (str "lib" library ".so")))]
        (if (and path (.isFile path))
          (let [p (ports/open-port (loader/jvm-loader) (fixture-spec library dir))]
            (try
              (let [ops (ports/call-op p "ops" {})
                    observations (conformance/observations p
                                  [{:op "ops" :request {} :expected ops}])]
                (is (= :jvm/ffm (ports/host p)))
                (is (conformance/conformant? observations) (pr-str observations))
                (is (vector? (:value ops))))
              (finally (ports/close! p))))
          (println "SKIP live-shared-libraries-conform-to-spi:" path "absent; card 20261007162504-2033bb50"))))))

(defn- canonical [x]
  (cond
    (map? x) (into [:map] (map (fn [[k v]] [k (canonical v)])
                              (sort-by (comp str key) x)))
    (sequential? x) (into [:seq] (map canonical x))
    :else x))

(deftest portable-reference-oracle
  (let [dir (System/getenv "HIVE_POLYGLOT_NATIVE")
        source-dir (or (System/getenv "HIVE_POLYGLOT_SOURCE")
                       (str (System/getProperty "user.home") "/PP/hive/hive-polyglot"))]
    (if (and dir (.isFile (io/file source-dir "resources/hive_polyglot/oracle_requests.edn")))
      (let [requests (:portable (edn/read-string (slurp (io/file source-dir
                                  "resources/hive_polyglot/oracle_requests.edn"))))
            expected (mapv edn/read-string (line-seq (io/reader
                         (io/file source-dir "test/resources/oracle_jvm.txt"))))
            opened (into {} (map (fn [library]
                                   [library (ports/open-port (loader/jvm-loader)
                                                             (fixture-spec library dir))])
                                 ["autopdf" "lazywal"]))]
        (try
          (let [actual (mapv (fn [{:keys [library op request]}]
                               [library op (canonical (ports/call-op (get opened library) op request))])
                             requests)]
            (is (= 10 (count actual)))
            (is (= expected actual)))
          (finally (doseq [p (vals opened)] (ports/close! p)))))
      (println "SKIP portable-reference-oracle: set HIVE_POLYGLOT_NATIVE and HIVE_POLYGLOT_SOURCE; card 20261007162504-2033bb50"))))