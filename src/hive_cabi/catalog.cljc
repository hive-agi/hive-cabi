(ns hive-cabi.catalog
  "Portable projection of a native ops envelope into host tool definitions."
  (:require [hive-spi.native.ports :as ports]
            [malli.core :as m]))

(defn op-rows
  "Return valid name/doc/pure rows from a successful ops envelope."
  [envelope]
  (if (and (ports/ok? envelope) (vector? (:value envelope)))
    (into [] (comp (filter #(and (string? (:name %)) (string? (:doc %))
                                 (boolean? (:pure %))))
                   (map #(select-keys % [:name :doc :pure]))) (:value envelope))
    []))

(m/=> op-rows [:=> [:cat :map] [:vector :map]])

(defn tool-defs
  "Project catalog rows to MCP tool definitions under the library prefix; sanitize op names."
  [library rows]
  (mapv (fn [{:keys [name doc]}]
          {:name (ports/tool-name library name)
           :description doc
           :inputSchema {:type "object" :additionalProperties true}})
        rows))

(m/=> tool-defs [:=> [:cat :string [:vector :map]] [:vector :map]])