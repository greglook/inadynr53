(ns build
  "Build instructions for Dynr53."
  (:require
    [clojure.tools.build.api :as b]))


(def basis (b/create-basis {:project "deps.edn"}))
(def class-dir "target/classes")
(def uber-file "target/dynr53.jar")


(defn clean
  "Remove compiled artifacts."
  [_]
  (b/delete {:path "target"}))


(defn uberjar
  [_]
  (clean nil)
  (b/copy-dir
    {:src-dirs ["resources"]
     :target-dir class-dir})
  (b/compile-clj
    {:basis basis
     :src-dirs ["src"]
     :class-dir class-dir
     :java-opts ["-Dclojure.spec.skip-macros=true"]
     :compile-opts {:elide-meta [:doc :file :line :added]
                    :direct-linking true}
     :bindings {#'clojure.core/*assert* false}})
  (b/uber
    {:basis basis
     :class-dir class-dir
     :uber-file uber-file
     :main 'dynr53.main}))
