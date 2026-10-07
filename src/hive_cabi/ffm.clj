(ns hive-cabi.ffm
  "JDK Panama FFM implementation of the hive-cabi/v1 INativePort."
  (:require [clojure.data.json :as json]
            [hive-spi.native.ports :as ports]
            [malli.core :as m])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker MemorySegment SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle]
           [java.nio.file Path]))

(defn- open-library [path]
  (let [arena (Arena/ofShared)]
    (try
      (let [lookup (SymbolLookup/libraryLookup (Path/of path (make-array String 0)) arena)
            ^Linker linker (Linker/nativeLinker)
            call (.downcallHandle linker
                                  ^MemorySegment (.orElseThrow (.find lookup "hive_call"))
                                  ^FunctionDescriptor (FunctionDescriptor/of ValueLayout/ADDRESS
                                    (into-array java.lang.foreign.MemoryLayout
                                                [ValueLayout/ADDRESS ValueLayout/ADDRESS]))
                                  (make-array java.lang.foreign.Linker$Option 0))
            free (.downcallHandle linker
                                  ^MemorySegment (.orElseThrow (.find lookup "hive_free"))
                                  ^FunctionDescriptor (FunctionDescriptor/ofVoid
                                    (into-array java.lang.foreign.MemoryLayout [ValueLayout/ADDRESS]))
                                  (make-array java.lang.foreign.Linker$Option 0))]
        {:arena arena :call call :free free})
      (catch Throwable error
        (.close arena)
        (throw error)))))

(defn- invoke-native [{:keys [^MethodHandle call ^MethodHandle free]} op request]
  (with-open [args (Arena/ofConfined)]
    (let [op-str (.allocateFrom args ^String op)
          payload (.allocateFrom args ^String (json/write-str request))
          ^MemorySegment result (.invokeWithArguments call (object-array [op-str payload]))]
      (when (= result MemorySegment/NULL)
        (throw (IllegalStateException. "hive_call returned NULL")))
      (try
        (json/read-str (.getString (.reinterpret result 10485760) 0) :key-fn keyword)
        (finally (.invokeWithArguments free (object-array [result])))))))

(defn- deliver-call [state work timeout-ms library]
  (let [task (future
               (try (work)
                    (finally
                      (locking state
                        (when (zero? (swap! (:active state) dec))
                          (when @(:closed state)
                            (.close ^Arena (:arena state))))))))
        result (deref task timeout-ms ::timeout)]
    (if (= ::timeout result)
      (ports/failure (str library ": native call timed out after " timeout-ms
                          " ms; downcall may still be running") :native/timeout)
      result)))

(defn- call-state [state library timeout-ms op request]
  (try
    (let [accepted? (locking state
                      (when-not @(:closed state)
                        (swap! (:active state) inc)
                        true))]
      (if-not accepted?
        (ports/failure (str library ": port closed") :native/unavailable)
        (let [answer (deliver-call state #(invoke-native state op request) timeout-ms library)]
          (if (and (map? answer) (boolean? (:ok answer)))
            answer
            (ports/failure (str library ": malformed native envelope") :native/malformed)))))
    (catch Throwable error
      (ports/failure (str library ": native call failed: " (.getMessage error)) :native/failed))))

(defn ffm-port
  "Open a shared hive-cabi/v1 library at path; return an unavailable port on failure.
   On timeout the shared arena survives until the native worker exits."
  [library path timeout-ms]
  (try
    (let [state (assoc (open-library path) :closed (atom false) :active (atom 0))]
      (reify ports/INativePort
        (library [_] library)
        (host [_] :jvm/ffm)
        (call-op [_ op request] (call-state state library timeout-ms op request))
        (close! [_]
          (locking state
            (when (compare-and-set! (:closed state) false true)
              (when (zero? @(:active state))
                (.close ^Arena (:arena state)))))
          nil)))
    (catch Throwable error
      (ports/unavailable-port library (str "Cannot load " path ": " (.getMessage error))))))

(m/=> ffm-port [:=> [:cat :string :string pos-int?] :any])