(ns dynr53.main
  "Main entry for Dynr53."
  (:gen-class)
  (:require
    [clojure.java.io :as io]
    [clojure.stacktrace :as cst]
    [clojure.string :as str]
    [dialog.logger :as log]
    [dynr53.config :as config]
    [dynr53.db :as db]
    [dynr53.server :as server]
    [dynr53.worker :as worker])
  (:import
    java.security.Security
    java.util.Properties
    (sun.misc
      Signal
      SignalHandler)))


(defn- print-usage
  "Print usage help for the tool."
  []
  (println "Usage: dynr53 [command]")
  (newline)
  (println "Commands:")
  (println "    server      Serve the dyndns API (default)")
  (println "    version     Show version information")
  (println "    help        Show usage help")
  (newline)
  (println "Configuration:")
  (doseq [option config/variables]
    (printf "    %-20s  %8s    %s\n"
            (:env option)
            (or (get config/defaults (:key option)) "")
            (:desc option)))
  (flush))


(defn- print-version
  "Print the version information."
  []
  (let [manifest (Properties.)]
    (try
      (with-open [rdr (io/reader (io/resource "META-INF/MANIFEST.MF"))]
        (.load manifest rdr))
      (catch Exception _
        _))
    (let [version (.getProperty manifest "Implementation-Version" "dev")
          commit (.getProperty manifest "Build-Commit" "HEAD")
          date (.getProperty manifest "Build-Date" "live")]
      (printf "dynr53 %s (built from %s on %s)\n" version commit date))))


(defn- configure-runtime!
  "Configure runtime JVM settings."
  []
  ;; AWS SDK best practice
  (Security/setProperty "networkaddress.cache.ttl" "60"))


(defn- handle-signals!
  "Install signal handlers for INT and TERM for clean shutdown."
  [shutdown finish]
  (let [handler (reify SignalHandler
                  (handle
                    [_ signal]
                    (log/debug "Received" (.getName ^Signal signal) "signal")
                    (deliver shutdown :signal)
                    (let [value (deref finish 5000 :timeout)]
                      (when (identical? value :timeout)
                        (log/error "Timed out waiting for system to shut down!")
                        (System/exit 130)))))]
    (Signal/handle (Signal. "INT") handler)
    (Signal/handle (Signal. "TERM") handler)))


(defn- run-system
  "Main system running logic."
  []
  (let [config (config/load-config)
        shutdown (promise)
        finish (promise)]
    (when (str/blank? (:zone-id config))
      (binding [*out* *err*]
        (println "No Route53 Hosted Zone specified")
        (System/exit 2)))
    (configure-runtime!)
    (handle-signals! shutdown finish)
    (let [db (db/initialize config)
          server (server/start! config db)
          worker (worker/start! db)]
      @shutdown
      (try
        (worker/stop! worker 1000)
        (server/stop! server 1000)
        (finally
          (deliver finish true))))))


(defn -main
  "Main entry point."
  [& args]
  (let [[command & more] args]
    (case command
      (nil "server")
      (try
        (run-system)
        (catch Exception ex
          (binding [*out* *err*]
            (cst/print-cause-trace ex)
            (flush)
            (System/exit 4))))

      ("help" "--help" "-h")
      (print-usage)

      "version"
      (print-version)

      ;; else
      (binding [*out* *err*]
        (println "Unknown dynr53 command:" command)
        (System/exit 1))))
  (flush)
  (System/exit 0))
