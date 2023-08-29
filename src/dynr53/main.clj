(ns dynr53.main
  "Main entry for Dynr53."
  (:gen-class)
  (:require
    [clojure.stacktrace :as cst]
    [clojure.tools.cli :as cli]
    [dialog.logger :as log]
    [dynr53.server :as server]
    [org.httpkit.server :as hks])
  (:import
    (sun.misc
      Signal
      SignalHandler)))


(def ^:private cli-options
  "Command-line tool options."
  [["-a" "--address IP-ADDR"         "IP address to bind HTTP server to"
    :default "0.0.0.0"]
   ["-p" "--port PORT"            "Port to bind HTTP server to"
    :default 8080
    :parse-fn parse-long
    :validate [pos? "Must be a positive number"]]
   [nil  "--basic-auth USER:PASS" "Enables basic authentication and requires the provider credentials match."]
   [nil  "--route53-zone ZONEID"  "Specify the Route53 zone to make updates in."]
   ["-h" "--help"                 "Show help and usage information."]])


(defn- print-usage
  "Print usage help for the tool."
  [summary]
  (println "Usage: dynr53 [options]")
  (newline)
  (println "Options:")
  (println summary))


(defn- handle-signals!
  "Install signal handlers for INT and TERM for clean shutdown."
  [exit-promise shutdown-promise]
  (let [handler (reify SignalHandler
                  (handle
                    [_ signal]
                    (log/debug "Received" (.getName ^Signal signal) "signal")
                    (deliver exit-promise :signal)
                    (let [value (deref shutdown-promise 1500 :timeout)]
                      (when (identical? value :timeout)
                        (log/error "Timed out waiting for server to shut down!")
                        (System/exit 130)))))]
    (Signal/handle (Signal. "INT") handler)
    (Signal/handle (Signal. "TERM") handler)))


(defn- run-server
  "Main system running logic."
  [options]
  (let [exit-promise (promise)
        shutdown-promise (promise)
        address (:address options)
        port (:port options)]
    (handle-signals! exit-promise shutdown-promise)
    (log/infof "Starting server on %s:%d" address port)
    (let [server (hks/run-server
                   server/handler
                   {:ip address
                    :port port
                    :server-header "dynr53"
                    :legacy-return-value? false})]
      @exit-promise
      (log/info "Shutting down server...")
      @(hks/server-stop! server {:tieout 1000})
      (deliver shutdown-promise true))))


(defn -main
  "Main entry point."
  [& raw-args]
  (let [parsed (cli/parse-opts raw-args cli-options)
        options (parsed :options)]
    ;; Print any option parse errors and abort.
    (when-let [errors (parsed :errors)]
      (binding [*out* *err*]
        (run! println errors)
        (flush)
        (System/exit 1)))
    ;; Reject any arguments.
    (when (seq (parsed :arguments))
      (binding [*out* *err*]
        (println "dynr53 does not accept any arguments")
        (flush)
        (System/exit 1)))
    ;; Show usage help.
    (when (:help options)
      (print-usage (parsed :summary))
      (flush)
      (System/exit 0))
    ;; Launch server process.
    (try
      (run-server options)
      (catch Exception ex
        (binding [*out* *err*]
          (cst/print-cause-trace ex)
          (flush)
          (System/exit 4))))
    (System/exit 0)))
