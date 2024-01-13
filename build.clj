(ns build
  "Build instructions for Dynr53."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.build.api :as b])
  (:import
    java.io.File
    java.time.LocalDate))


(def basis (b/create-basis {:project "deps.edn"}))
(def major-version "0.3")

(def src-dir "src")
(def class-dir "target/classes")
(def uber-jar "target/dynr53.jar")


;; ## Utilities

(defn clean
  "Remove compiled artifacts."
  [_]
  (b/delete {:path "target"}))


(defn- last-modified
  "Return the newest modification time in epoch milliseconds from all of the
  files in the given file arguments. Directories are traversed recursively."
  [& paths]
  (reduce
    (fn max-inst
      [newest ^File file]
      (if (.isFile file)
        (let [candidate (.lastModified file)]
          (if (< newest candidate)
            candidate
            newest))
        newest))
    0
    (mapcat
      (comp file-seq io/file)
      paths)))


;; ## Version Tools

(defn- version-info
  "Compute the current version."
  [_]
  {:version (str major-version "." (b/git-count-revs nil))
   :commit (b/git-process {:git-args "rev-parse HEAD"})
   :date (str (LocalDate/now))})


(defn print-version
  "Print the current version information."
  [opts]
  (let [{:keys [version commit date]} (version-info opts)]
    (printf "dynr53 %s (built from %s on %s)\n" version commit date)
    (flush)))


(defn- update-changelog
  "Stamp the CHANGELOG file with the new version."
  [version]
  (let [file (io/file "CHANGELOG.md")
        today (LocalDate/now)
        changelog (slurp file)]
    (when (str/includes? changelog "## [Unreleased]\n\n...\n")
      (binding [*out* *err*]
        (println "Changelog does not appear to have been updated with changes, aborting")
        (System/exit 3)))
    (-> changelog
        (str/replace #"## \[Unreleased\]"
                     (str "## [Unreleased]\n\n...\n\n\n"
                          "## [" version "] - " today))
        (str/replace #"\[Unreleased\]: (\S+/compare)/(\S+)\.\.\.HEAD"
                     (str "[Unreleased]: $1/" version "...HEAD\n"
                          "[" version "]: $1/$2..." version))
        (->> (spit file)))))


(defn prep-release
  "Prepare the repository for release."
  [_]
  (let [status (b/git-process {:git-args "status --porcelain --untracked-files=no"})]
    (when-not (str/blank? status)
      (binding [*out* *err*]
        (println "Uncommitted changes in local repository, aborting")
        (System/exit 2))))
  (let [new-version (str major-version "." (inc (parse-long (b/git-count-revs nil))))]
    (update-changelog new-version)
    (b/git-process {:git-args ["commit" "-am" (str "Stamping release " new-version)]})
    (b/git-process {:git-args ["tag" new-version "-s" "-m" (str "Release " new-version)]})
    (println "Prepared release for" new-version)))


;; ## Native Image

(defn uberjar
  [opts]
  (let [version (version-info opts)
        uber-file (io/file uber-jar)]
    (when (or (not (.exists uber-file))
              (< (last-modified uber-file)
                 (last-modified "deps.edn" src-dir)))
      (b/compile-clj
        {:basis basis
         :src-dirs [src-dir]
         :class-dir class-dir
         :java-opts ["-Dclojure.spec.skip-macros=true"]
         :compile-opts {:elide-meta [:doc :file :line :added]
                        :direct-linking true}
         :bindings {#'clojure.core/*assert* false}})
      (b/uber
        {:basis basis
         :class-dir class-dir
         :uber-file uber-jar
         :main 'dynr53.main
         :manifest {"Implementation-Title" "Dynr53"
                    "Implementation-Version" (:version version)
                    "Build-Commit" (:commit version)
                    "Build-Date" (:date version)}}))
    (assoc opts :version version)))


(defn- graal-check
  "Verify that the Oracle Graal runtime and native-image tool are available.
  Returns the options updated with a `:graal-home` setting on success."
  [opts]
  ;; Check vendor for GraalVM name.
  (let [vendor (System/getProperty "java.vendor")
        version (System/getProperty "java.version")]
    (when-not (and vendor (str/starts-with? vendor "GraalVM")
                   version (str/starts-with? version "21."))
      (binding [*out* *err*]
        (println "Compile the native-image with GraalVM 21 or higher - current JDK:"
                 (or (System/getProperty "java.vm.version") version))
        (println "Download from https://github.com/graalvm/graalvm-ce-builds/releases and extract to ~/.local/share/graalvm/")
        (System/exit 2))))
  ;; Can now assume that java.home is GRAAL_HOME, check for native-image tool.
  (let [graal-home (io/file (System/getProperty "java.home"))
        native-image-cmd (io/file graal-home "bin/native-image")]
    (when-not (.exists native-image-cmd)
      (binding [*out* *err*]
        (println "GraalVM native-image tool missing from" (str native-image-cmd))
        (println "If necessary, run:" (str graal-home "/bin/gu") "install native-image")
        (System/exit 2)))
    (assoc opts :graal-native-image native-image-cmd)))


(defn native-image
  "Compile the uberjar to a native image."
  [opts]
  (let [opts (-> opts graal-check uberjar)
        args [(str (:graal-native-image opts))
              "-jar" uber-jar
              "-o" "target/dynr53"
              "-march=x86-64-v2"
              ;; Include manifest for version injection, other common options.
              "-H:+UnlockExperimentalVMOptions"
              "-H:IncludeResources=^META-INF/MANIFEST.MF$"
              "-H:+ReportExceptionStackTraces"
              ;; Build-time resource controls.
              #_"-J-Xms3G"
              "-J-Xmx3G"
              ;; Run-time resource controls.
              "-R:MinHeapSize=5m"
              "-R:MaxHeapSize=128m"
              "-R:MaxNewSize=2m"
              ;; Preinitialize Clojure namespaces with clj-easy.
              "--features=clj_easy.graal_build_time.InitClojureClasses"
              "--report-unsupported-elements-at-runtime"
              "--enable-preview"
              "--no-fallback"
              ;; Verbose output if enabled.
              (when (:verbose opts)
                ["--native-image-info"
                 "--verbose"])
              ;; Static build flag
              (when (:graal-static opts)
                "--static")]
        result (b/process {:command-args (remove nil? (flatten args))})]
    (when-not (zero? (:exit result))
      (binding [*out* *err*]
        (println "Building native-image failed")
        (prn result)
        (System/exit 3)))
    opts))
