(ns hive-cabi.loader
  "JVM INativeLoader selecting and resolving the FFM shared artifact."
  (:require [hive-cabi.ffm :as ffm]
            [hive-cabi.resolve :as resolve]
            [hive-spi.native.ports :as ports]
            [hive-spi.native.schema :as schema]
            [malli.core :as m]))

(defn jvm-loader
  "Build a JVM loader; unloadable or unsupported LibrarySpecs produce inert ports."
  []
  (reify ports/INativeLoader
    (transports [_] #{:jvm/ffm})
    (open-port [_ spec]
      (let [library (:native/library spec)]
        (try
          (cond
            (not (schema/valid? schema/LibrarySpec spec))
            (ports/unavailable-port library "Invalid hive-cabi/v1 LibrarySpec")
            (and (seq (:native/transports spec))
                 (not (some #{:jvm/ffm} (:native/transports spec))))
            (ports/unavailable-port library "No JVM FFM transport in LibrarySpec")
            :else
            (if-let [path (resolve/library-path spec)]
              (ffm/ffm-port library path (or (:native/timeout-ms spec) 30000))
              (ports/unavailable-port library
                                      (str library ": shared artifact unavailable for " (resolve/os-arch)))))
          (catch Exception error
            (ports/unavailable-port library (str library ": " (.getMessage error)))))))))

(m/=> jvm-loader [:=> [:cat] :any])