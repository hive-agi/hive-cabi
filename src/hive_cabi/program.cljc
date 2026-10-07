(ns hive-cabi.program
  "Generic lazy IAddon for one hive-cabi/v1 program library."
  (:require [hive-addon.protocol :as addon]
            [hive-cabi.catalog :as catalog]
            [hive-spi.native.ports :as ports]
            [hive-spi.native.registry :as registry]
            [malli.core :as m]))

(defn- safe-call [port op request]
  (try
    (let [answer (ports/call-op port op request)]
      (if (and (map? answer) (boolean? (:ok answer)))
        answer
        (ports/failure "Malformed native envelope" :native/malformed)))
    (catch #?(:cljs :default :default Exception) error
      (ports/failure (str "Native call failed: " error) :native/failed))))

(defn- tool-result
  "Project an envelope into an MCP tool result while preserving host error type."
  [envelope]
  (if (ports/ok? envelope)
    {:content [{:type "text" :text (pr-str (:value envelope))}]}
    (cond-> {:isError true
             :content [{:type "text" :text (str (or (:error envelope) "Native call failed"))}]}
      (:error/type envelope) (assoc :error/type (:error/type envelope)))))

(defn program-addon
  "Build a lazy program IAddon from an addon id and LibrarySpec; loader is read at call time."
  [{:keys [addon/id library-spec] :as options}]
  (let [library (:native/library library-spec)
        held (atom nil)
        rows (atom (when (vector? (:catalog options))
                     (catalog/op-rows {:ok true :value (:catalog options)})))
        verified (atom nil)]
    (letfn [(port! []
              (when-let [loader (registry/loader)]
                (let [{:keys [provider port]} @held]
                  (if (and (identical? loader provider) port)
                    port
                    (do
                      (when port (ports/close! port))
                      (reset! verified nil)
                      (let [opened (ports/open-port loader library-spec)]
                        (reset! held {:provider loader :port opened})
                        opened))))))
            (catalog! []
              (when-let [native (port!)]
                (when-not (identical? native @verified)
                  (let [answer (safe-call native "ops" {})]
                    (when (and (ports/ok? answer) (vector? (:value answer)))
                      (reset! rows (catalog/op-rows answer))
                      (reset! verified native)))))
              @rows)
            (invoke! [operation params]
              (if-let [native (port!)]
                (tool-result (safe-call native operation (or params {})))
                (tool-result (ports/failure "mount hive-cabi (capability :native/loader)"
                                            :native/unavailable))))]
      (reify addon/IAddon
        (addon-id [_] (or id (str "hive.cabi." library)))
        (addon-type [_] :native)
        (capabilities [_] #{:tools :health-reporting})
        (initialize! [_ _] {:success? true :errors []})
        (shutdown! [_]
          (when-let [native (:port @held)] (ports/close! native))
          (reset! held nil)
          (reset! verified nil)
          nil)
        (tools [_]
          (mapv (fn [definition row]
                  (assoc definition :handler #(invoke! (:name row) %)))
                (catalog/tool-defs library (or (catalog!) [])) (or @rows [])))
        (schema-extensions [_] [])
        (health [_]
          (let [native (port!)
                _ (catalog!)
                ready? (and native (identical? native @verified))]
            (if ready?
              {:status :ok :details {:library library}}
              {:status :degraded
               :details {:library library
                         :message (if native "Native catalog unavailable"
                                      "mount hive-cabi (capability :native/loader)")}})))
        (excluded-tools [_] #{})
        (hooks [_] {})))))

(m/=> program-addon [:=> [:cat :map] :any])

(defn addon-ctor
  "Construct a program addon from a mount config carrying :library-spec and :addon/id."
  [config]
  (when (:library-spec config)
    (program-addon config)))

(m/=> addon-ctor [:=> [:cat :map] :any])