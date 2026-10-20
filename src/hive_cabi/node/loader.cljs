(ns hive-cabi.node.loader
  "Node native loader over the hive-spi INativeLoader seam."
  (:require [hive-spi.native.ports :as port]
            [hive-cabi.node.ffi :as ffi]
            [hive-cabi.node.wasm :as wasm]
            [malli.core :as m]))

(defn os-arch
  "Map Node platform and architecture to the LibrarySpec artifact key."
  []
  (keyword (str (case (.-platform js/process)
                  "win32" "windows" "darwin" "darwin" "linux" "linux" (.-platform js/process))
                "-"
                (case (.-arch js/process) "x64" "x86_64" "arm64" "aarch64" (.-arch js/process)))))

(m/=> os-arch [:=> [:cat] :keyword])

(defn create-loader
  "Provide Node FFI and js/wasm transports; absent artifacts return inert ports."
  []
  (reify port/INativeLoader
    (transports [_] #{:node/ffi :node/wasm})
    (open-port [_ spec]
      (let [library (:native/library spec)
            artifacts (:native/artifacts spec)
            shared (get-in artifacts [(os-arch) :shared])
            js-wasm (get-in artifacts [:wasm :js])
            prefs (or (:native/transports spec) [:node/ffi :node/wasm])]
        (cond
          (and (some #{:node/ffi} prefs) shared) (ffi/open-port library shared)
          (and (some #{:node/wasm} prefs) js-wasm)
          (wasm/open-port library js-wasm (or (get-in artifacts [:wasm :exec])
                                               (.join (js/require "path") (.dirname (js/require "path") js-wasm) "wasm_exec.js")))
          :else (port/unavailable-port library "No Node artifact in LibrarySpec"))))))

(m/=> create-loader [:=> [:cat] :any])