(ns hive-cabi.node.ffi
  "Node koffi adapter for hive-cabi/v1 shared libraries."
  (:require [hive-spi.native.ports :as port]
            [malli.core :as m]
            ["koffi" :as koffi]))

(defn open-port
  "Open shared library with koffi; every hive_call pointer is freed exactly once."
  [library artifact]
  (try
    (let [dll (.load koffi artifact)
          call (.func dll "void *hive_call(const char *op, const char *request_json)")
          free (.func dll "void hive_free(void *result)")]
      (reify port/INativePort
        (library [_] library)
        (host [_] :node/ffi)
        (call-op [_ op request]
          (try
            (let [ptr (call op (.stringify js/JSON (clj->js request)))]
              (if (nil? ptr)
                (port/failure "hive_call returned null" :native/malformed)
                (try
                  (let [out (js->clj (.parse js/JSON (.decode koffi ptr "char" -1)) :keywordize-keys true)]
                    (if (boolean? (:ok out)) out
                        (port/failure "Invalid native envelope" :native/malformed)))
                  (finally (free ptr)))))
            (catch :default e (port/failure (str e) :native/failed))))
        (close! [_] (.unload dll) nil)))
    (catch :default e (port/unavailable-port library (str e)))))

(m/=> open-port [:=> [:cat :string :string] :any])