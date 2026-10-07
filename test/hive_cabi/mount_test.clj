(ns hive-cabi.mount-test
  "Real mount solver ordering and live hive-cabi fixture program integration."
  (:require [clojure.test :refer [deftest is]]
            [hive-addon.mount :as mount]
            [hive-addon.mount.port :as host]
            [hive-addon.protocol :as addon]
            [hive-cabi.resolve :as resolve]
            [hive-spi.native.registry :as registry]))

(defn- provider-spec []
  {:addon/id "hive.cabi"
   :addon/type :native
   :addon/init-ns "hive-cabi.init"
   :addon/init-fn "create-addon"
   :addon/trust-class :foss
   :addon/capabilities #{:native/loader}})

(defn- program-spec [path]
  {:addon/id "fixture.autopdf"
   :addon/type :native
   :addon/init-ns "hive-cabi.program"
   :addon/init-fn "addon-ctor"
   :addon/trust-class :foss
   :addon/requires-capabilities #{:native/loader}
   :addon/config {:addon/id "fixture.autopdf"
                  :library-spec {:native/library "autopdf"
                                 :native/abi :hive-cabi/v1
                                 :native/artifacts {(resolve/os-arch) {:shared path}}}}})

(deftest provider-manifest-is-discoverable
  (let [{:keys [specs errors]} (mount/discover-specs)]
    (is (empty? errors) (pr-str errors))
    (is (some #(= "hive.cabi" (:addon/id %)) specs))))

(deftest solver-mounts-provider-before-program
  (if-let [dir (System/getenv "HIVE_POLYGLOT_NATIVE")]
    (let [specs [(program-spec (str dir "/libautopdf.so")) (provider-spec)]
          plan (mount/solve specs)
          mounted (mount/atom-mount-host)
          report (mount/mount! plan mounted)]
      (try
        (is (= ["hive.cabi" "fixture.autopdf"]
               (mapv :addon/id (:ordered plan))))
        (is (empty? (:unmet-capabilities plan)))
        (is (:ok? report) (pr-str report))
        (let [instance (host/registered mounted "fixture.autopdf")
              tools (addon/tools instance)
              op (first (filter #(= "autopdf_ops" (:name %)) tools))]
          (is (= :ok (:status (addon/health instance))))
          (is (seq tools))
          (is (some? op))
          (is (not (:isError ((:handler op) {})))))
        (finally
          (mount/teardown! mounted (mapv :addon/id (:ordered plan)))
          (is (nil? (registry/loader))))))
    (println "SKIP solver-mounts-provider-before-program: HIVE_POLYGLOT_NATIVE absent; card 20261007162504-2033bb50")))

(deftest solver-mounts-photocraft-program
  (let [dir (System/getenv "HIVE_POLYGLOT_NATIVE")
        path (when dir (str dir "/libphotocraft.so"))]
    (if (and path (.isFile (java.io.File. path)))
      (let [program (-> (program-spec path)
                        (assoc :addon/id "fixture.photocraft")
                        (assoc-in [:addon/config :addon/id] "fixture.photocraft")
                        (assoc-in [:addon/config :library-spec :native/library] "photocraft"))
            plan (mount/solve [program (provider-spec)])
            mounted (mount/atom-mount-host)
            started (System/nanoTime)
            report (mount/mount! plan mounted)]
        (try
          (is (= ["hive.cabi" "fixture.photocraft"] (mapv :addon/id (:ordered plan))))
          (is (:ok? report) (pr-str report))
          (let [instance (host/registered mounted "fixture.photocraft")
                tools (addon/tools instance)
                method (first (filter #(= "photocraft_methods" (:name %)) tools))]
            (println "LIVE CRAFT photocraft mount+tools-ms" (/ (- (System/nanoTime) started) 1000000.0))
            (is (= 16 (count tools)))
            (is (some? method))
            (when method (is (not (:isError ((:handler method) {}))))))
          (finally (mount/teardown! mounted (mapv :addon/id (:ordered plan)))
                   (is (nil? (registry/loader))))))
      (println "SKIP solver-mounts-photocraft-program:" path "absent; card 20261007162504-2033bb50"))))