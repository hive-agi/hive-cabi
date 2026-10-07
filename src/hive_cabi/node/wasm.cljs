(ns hive-cabi.node.wasm
  "Node Go js/wasm fallback transport; loading returns a Promise of INativePort."
  (:require [hive-spi.native.ports :as port]
            [malli.core :as m]))

(defn open-port
  "Instantiate a Go js/wasm library; a missing artifact yields an unavailable port."
  [library artifact wasm-exec]
  (try
    (let [fs (js/require "fs")]
      (js/require wasm-exec)
      (let [go (js/Go.)
            bytes (.readFileSync fs artifact)]
        (-> (.instantiate js/WebAssembly bytes (.-importObject go))
            (.then (fn [result]
                     (-> (.run go (.-instance result))
                         (.catch (fn [error] (js/console.error "Go wasm exited" error))))
                     (let [name (str "hive_" library)]
                       (if-not (fn? (aget js/globalThis name))
                         (port/unavailable-port library (str name " not registered"))
                         (reify port/INativePort
                           (library [_] library)
                           (host [_] :node/wasm)
                           (call-op [_ op request]
                             (try
                               (let [out (js->clj (.parse js/JSON ((aget js/globalThis name) op (.stringify js/JSON (clj->js request)))) :keywordize-keys true)]
                                 (if (boolean? (:ok out)) out
                                     (port/failure "Invalid native envelope" :native/malformed)))
                               (catch :default error (port/failure (str error) :native/failed))))
                           (close! [_] nil))))))
            (.catch (fn [error] (port/unavailable-port library (str error)))))))
    (catch :default error (js/Promise.resolve (port/unavailable-port library (str error))))))

(m/=> open-port [:=> [:cat :string :string :string] :any])