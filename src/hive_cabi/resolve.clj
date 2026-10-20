(ns hive-cabi.resolve
  "Resolve LibrarySpec shared artifacts to filesystem paths on the JVM."
  (:require [clojure.java.io :as io]
            [malli.core :as m])
  (:import [java.nio.file Files Path StandardCopyOption]
           [java.security MessageDigest]
           [java.util HexFormat]))

(defn os-arch
  "Return the LibrarySpec key for this supported JVM operating system and architecture."
  []
  (let [os (.toLowerCase (System/getProperty "os.name"))
        arch (.toLowerCase (System/getProperty "os.arch"))
        system (cond (.contains os "linux") "linux"
                     (.contains os "mac") "darwin"
                     (.contains os "windows") "windows")
        machine (case arch
                  ("amd64" "x86_64") "x86_64"
                  ("aarch64" "arm64") "aarch64"
                  nil)]
    (when (and system machine) (keyword (str system "-" machine)))))

(m/=> os-arch [:=> [:cat] [:maybe :keyword]])

(defn- digest [bytes]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.formatHex (HexFormat/of) (.digest md bytes))))

(defn resolve-artifact
  "Resolve an absolute shared path unchanged, or extract a classpath resource to a content-hashed user cache; nil if missing."
  [artifact]
  (when (string? artifact)
    (let [source (io/file artifact)]
      (if (.isAbsolute source)
        (when (.isFile source) (.getAbsolutePath source))
        (when-let [url (io/resource artifact)]
          (let [bytes (with-open [stream (io/input-stream url)] (.readAllBytes stream))
                hash (digest bytes)
                ^Path directory (Path/of (System/getProperty "user.home")
                                         (into-array String [".cache" "hive-cabi" hash]))
                ^Path target (.resolve directory (.getName source))]
            (Files/createDirectories directory (make-array java.nio.file.attribute.FileAttribute 0))
            (when-not (Files/isRegularFile target (make-array java.nio.file.LinkOption 0))
              (let [^Path temp (Files/createTempFile directory "artifact-" ".tmp"
                                                     (make-array java.nio.file.attribute.FileAttribute 0))]
                (try
                  (Files/write temp bytes (make-array java.nio.file.OpenOption 0))
                  (Files/move temp target (into-array java.nio.file.CopyOption
                                                    [StandardCopyOption/REPLACE_EXISTING]))
                  (finally (Files/deleteIfExists temp)))))
            (str target)))))))

(m/=> resolve-artifact [:=> [:cat :any] [:maybe :string]])

(defn library-path
  "Find the shared artifact selected by the current os-arch in a LibrarySpec."
  [spec]
  (some-> spec :native/artifacts (get (os-arch)) :shared resolve-artifact))

(m/=> library-path [:=> [:cat :map] [:maybe :string]])