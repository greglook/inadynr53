(ns build
  "Build instructions for Dynr53."
  (:require
    [clojure.tools.build.api :as b])
  (:import
    java.time.LocalDate))


(def basis (b/create-basis {:project "deps.edn"}))
(def version-prefix "0.1")
(def class-dir "target/classes")
(def uber-file "target/dynr53.jar")


(defn clean
  "Remove compiled artifacts."
  [_]
  (b/delete {:path "target"}))


(defn- version-info
  "Compute the current version."
  []
  {:version (str version-prefix "." (b/git-count-revs nil))
   :commit (b/git-process {:git-args "rev-parse HEAD"})
   :date (str (LocalDate/now))})


(defn print-version
  [_]
  (let [{:keys [version commit date]} (version-info)]
    (printf "dynr53 %s (built %s from %s)\n" version date commit)
    (flush)))


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
  (let [{:keys [version commit date]} (version-info)]
    (b/uber
      {:basis basis
       :class-dir class-dir
       :uber-file uber-file
       :main 'dynr53.main
       :manifest {"Implementation-Title" "Dynr53"
                  "Implementation-Version" version
                  "Build-Commit" commit
                  "Build-Date" date}})))
