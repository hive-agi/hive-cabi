(ns hive-cabi.loader-test
  "Contracts for native catalog, loader absence, resource extraction and live FFM."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-cabi.catalog :as catalog]
            [hive-cabi.init :as init]
            [hive-cabi.loader :as loader]
            [hive-cabi.program :as program]
            [hive-cabi.resolve :as resolve]
            [hive-spi.native.ports :as ports]
            [hive-spi.native.registry :as registry]
            [hive-spi.native.schema :as schema]
            [hive-schemas.test :as hst])
  (:import [java.nio.file Files Path]))

(defn catalog-tools
  "Project a schema-valid op catalog into the library's tool names."
  [rows]
  (catalog/tool-defs "fixture" rows))

(deftest catalog-names-obey-spi-toolname
  (let [name (:name (first (catalog/tool-defs "photo.craft"
                    [{:name "shape.svg" :doc "Render" :pure true}])))
        long-name (:name (first (catalog/tool-defs "photo"
                    [{:name (apply str (repeat 100 "a")) :doc "Long" :pure true}])))]
    (is (= "photo_craft_shape_svg" name))
    (is (schema/valid? schema/ToolName name))
    (is (schema/valid? schema/ToolName long-name))))

(hst/deftrifecta-from-schema catalog-projection
  hive-cabi.loader-test/catalog-tools
  {:in schema/OpCatalog
   :out [:vector :map]
   :rel (fn [rows tools]
          (and (= (count rows) (count tools))
               (every? true? (map (fn [row tool]
                                    (and (= (:name (first (catalog/tool-defs "fixture" [row]))) (:name tool))
                                         (schema/valid? schema/ToolName (:name tool))
                                         (= (:doc row) (:description tool)))) rows tools))))
   :mutation false})

(defn- fixture-spec [library path]
  {:native/library library
   :native/abi :hive-cabi/v1
   :native/artifacts {(resolve/os-arch) {:shared path}}
   :native/timeout-ms 30000
   :native/transports [:jvm/ffm]})

(deftest resource-and-unavailable-laws
  (testing "classpath fixture extracts deterministically under the content-hashed cache"
    (let [artifact "native/test/fixture.txt"
          first-path (resolve/resolve-artifact artifact)]
      (is (string? first-path))
      (is (= first-path (resolve/resolve-artifact artifact)))
      (is (Files/isRegularFile (Path/of first-path (make-array String 0))
                               (make-array java.nio.file.LinkOption 0)))
      (is (.contains first-path (str (System/getProperty "user.home") "/.cache/hive-cabi/")))))
  (let [p (ports/open-port (loader/jvm-loader) (fixture-spec "missing" "/no/such/native.so"))]
    (is (= :native/unavailable (:error/type (ports/call-op p "ops" {}))))
    (is (nil? (ports/close! p)))))

(deftest lazy-program-and-provider-lifecycle
  (let [spec (fixture-spec "fixture" "/no/such/native.so")
        instance (program/program-addon {:addon/id "fixture.native" :library-spec spec
                                         :catalog [{:name "ping" :doc "Ping" :pure true}]})
        provider (init/create-addon {})]
    (try
      (is (= :degraded (:status (addon/health instance))))
      (is (.contains (get-in (addon/health instance) [:details :message]) "mount hive-cabi"))
      (is (= ["fixture_ping"] (mapv :name (addon/tools instance))))
      (is (:isError ((:handler (first (addon/tools instance))) {})))
      (is (nil? (registry/loader)))
      (is (:success? (addon/initialize! provider {})))
      (is (identical? (registry/loader) (registry/loader)))
      (is (= :degraded (:status (addon/health instance))))
      (finally
        (addon/shutdown! instance)
        (addon/shutdown! provider)
        (is (nil? (registry/loader)))))))

(deftest success-only-cache-and-loader-replacement
  (let [calls (atom 0)
        closing (atom 0)
        available? (atom false)
        loader (reify ports/INativeLoader
                 (transports [_] #{:jvm/ffm})
                 (open-port [_ _]
                   (reify ports/INativePort
                     (library [_] "stub")
                     (host [_] :jvm/ffm)
                     (call-op [_ op _]
                       (swap! calls inc)
                       (if (and (= op "ops") @available?)
                         {:ok true :value [{:name "ping" :doc "Ping" :pure true}]}
                         (ports/failure "not ready" :native/unavailable)))
                     (close! [_] (swap! closing inc) nil))))
        instance (program/program-addon {:addon/id "stub" :library-spec
                                          (fixture-spec "stub" "/no/such/native.so")})]
    (try
      (registry/register-loader! loader)
      (is (empty? (addon/tools instance)))
      (is (= :degraded (:status (addon/health instance))))
      (reset! available? true)
      (is (= ["stub_ping"] (mapv :name (addon/tools instance))))
      (let [once @calls]
        (dotimes [_ 3] (addon/tools instance))
        (is (= once @calls) "successful catalog cached per opened library"))
      (registry/unregister-loader! loader)
      (is (= :degraded (:status (addon/health instance))))
      (is (= ["stub_ping"] (mapv :name (addon/tools instance)))
          "cached catalog remains discoverable without provider")
      (is (= :native/unavailable (:error/type ((:handler (first (addon/tools instance))) {}))))
      (finally
        (addon/shutdown! instance)
        (registry/unregister-loader! loader)
        (is (= 1 @closing))))))

(deftest live-ffm-ports
  (if-let [dir (System/getenv "HIVE_POLYGLOT_NATIVE")]
    (doseq [library ["autopdf" "lazywal"]]
      (let [spec (fixture-spec library (str dir "/lib" library ".so"))
            port (ports/open-port (loader/jvm-loader) spec)]
        (try
          (is (= :jvm/ffm (ports/host port)))
          (is (ports/ok? (ports/call-op port "ops" {})))
          (is (vector? (:value (ports/call-op port "ops" {}))))
          (finally (ports/close! port)))))
    (println "SKIP live-ffm-ports: HIVE_POLYGLOT_NATIVE absent; card 20261007162504-2033bb50")))

(deftest live-craft-ffm-ports
  (let [dir (System/getenv "HIVE_POLYGLOT_NATIVE")]
    (doseq [[library count-ops operation] [["photocraft" 16 "methods"]
                                           ["vectorcraft" 17 "ui.tool.list"]]]
      (let [path (when dir (str dir "/lib" library ".so"))]
        (if (and path (Files/isRegularFile (Path/of path (make-array String 0))
                                 (make-array java.nio.file.LinkOption 0)))
          (let [started (System/nanoTime)
                port (ports/open-port (loader/jvm-loader) (fixture-spec library path))]
            (try
              (let [opened-ms (/ (- (System/nanoTime) started) 1000000.0)
                    catalog-start (System/nanoTime)
                    ops (ports/call-op port "ops" {})
                    catalog-ms (/ (- (System/nanoTime) catalog-start) 1000000.0)
                    call-start (System/nanoTime)
                    response (ports/call-op port operation {})
                    call-ms (/ (- (System/nanoTime) call-start) 1000000.0)]
                (println "LIVE CRAFT" library "open-ms" opened-ms "ops-ms" catalog-ms operation "ms" call-ms)
                (is (= :jvm/ffm (ports/host port)))
                (is (ports/ok? ops) (pr-str ops))
                (is (= count-ops (count (:value ops))))
                (is (ports/ok? response) (pr-str response)))
              (finally (ports/close! port))))
          (println "SKIP live-craft-ffm-ports:" path "absent; card 20261007162504-2033bb50"))))))