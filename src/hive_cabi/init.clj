(ns hive-cabi.init
  "IAddon provider publishing the JVM native loader through hive-spi's registry."
  (:require [hive-addon.protocol :as addon]
            [hive-cabi.loader :as loader]
            [hive-spi.native.registry :as registry]
            [malli.core :as m]))

(defn create-addon
  "Construct the native loader provider; initialize registers and shutdown unregisters exactly its loader."
  [_config]
  (let [native-loader (loader/jvm-loader)]
    (reify addon/IAddon
      (addon-id [_] "hive.cabi")
      (addon-type [_] :native)
      (capabilities [_] #{:native/loader})
      (initialize! [_ _]
        (registry/register-loader! native-loader)
        {:success? true :errors []})
      (shutdown! [_]
        (registry/unregister-loader! native-loader)
        nil)
      (tools [_] [])
      (schema-extensions [_] [])
      (health [_] {:status :ok :details {:transports #{:jvm/ffm}}})
      (excluded-tools [_] #{})
      (hooks [_] {}))))

(m/=> create-addon [:=> [:cat :map] :any])