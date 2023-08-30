(ns dynr53.main
  "Main entry for Dynr53."
  (:gen-class)
  (:require
    [clojure.stacktrace :as cst]
    [dialog.logger :as log]
    [dynr53.config :as config]
    [dynr53.db :as db]
    [dynr53.server :as server]
    [dynr53.worker :as worker])
  (:import
    (sun.misc
      Signal
      SignalHandler)))


(defn- print-usage
  "Print usage help for the tool."
  []
  (println "Usage: dynr53 [--help]")
  (newline)
  (println "Configuration:")
  (doseq [option config/variables]
    (printf "    %-20s  %8s    %s\n"
            (:env option)
            (or (get config/defaults (:key option)) "")
            (:desc option)))
  (flush))


(defn- handle-signals!
  "Install signal handlers for INT and TERM for clean shutdown."
  [exit-promise shutdown-promise]
  (let [handler (reify SignalHandler
                  (handle
                    [_ signal]
                    (log/debug "Received" (.getName ^Signal signal) "signal")
                    (deliver exit-promise :signal)
                    (let [value (deref shutdown-promise 5000 :timeout)]
                      (when (identical? value :timeout)
                        (log/error "Timed out waiting for system to shut down!")
                        (System/exit 130)))))]
    (Signal/handle (Signal. "INT") handler)
    (Signal/handle (Signal. "TERM") handler)))


(defn- run-system
  "Main system running logic."
  []
  (let [exit-promise (promise)
        shutdown-promise (promise)]
    (handle-signals! exit-promise shutdown-promise)
    (let [config (config/load-config)
          db (db/initialize)
          server (server/start! config db)
          worker (worker/start! config db)]
      @exit-promise
      (try
        (worker/stop! worker 1000)
        (server/stop! server 1000)
        (finally
          (deliver shutdown-promise true))))))


(defn -main
  "Main entry point."
  [& args]
  ;; Check for help option.
  (when (contains? #{"-h" "--help" "help"} (first args))
    (print-usage)
    (System/exit 0))
  ;; Reject any other arguments.
  (when (seq args)
    (binding [*out* *err*]
      (println "dynr53 does not accept any arguments")
      (System/exit 1)))
  ;; Run system otherwise
  (try
    (run-system)
    (catch Exception ex
      (binding [*out* *err*]
        (cst/print-cause-trace ex)
        (flush)
        (System/exit 4))))
  (flush)
  (System/exit 0))
