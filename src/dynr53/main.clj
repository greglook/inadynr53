(ns dynr53.main
  "Main entry for Dynr53."
  (:gen-class)
  (:require
    [clojure.stacktrace :as cst]
    [clojure.tools.cli :as cli]
    [dialog.logger :as log]
    [dynr53.server :as server]
    [org.httpkit.server :as hks]))


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
    ;; Launch server.
    (try
      (let [exit-promise (promise)
            address (:address options)
            port (:port options)]
        ;; TODO: register shutdown hook?
        (log/infof "Starting server on %s:%d" address port)
        (let [server (hks/run-server
                       server/handler
                       {:ip address
                        :port port
                        :server-header "dynr53"
                        :legacy-return-value? false})]
          ;; Block until exit delivered.
          @exit-promise
          ;; Shutdown server gracefully.
          (log/info "Shutting down server...")
          @(hks/server-stop! server {:tieout 1000})))
      (catch Exception ex
        (binding [*out* *err*]
          (cst/print-cause-trace ex)
          (flush)
          (System/exit 4))))
    ;; Successful run if no other exit.
    (System/exit 0)))
