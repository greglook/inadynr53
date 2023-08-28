(ns dynr53.main
  "Main entry for Dynr53."
  (:gen-class)
  (:require
    [clojure.stacktrace :as cst]
    [clojure.tools.cli :as cli]
    [dialog.logger :as log]))


(def ^:private cli-options
  "Command-line tool options."
  [[nil  "--basic-auth USER:PASS" "Enables basic authentication and requires the provider credentials match."]
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
      (let [shutdown (promise)]
        ;; TODO: register shutdown hook?
        (log/info "Starting up...")
        ;; TODO: launch server
        @shutdown)
      (catch Exception ex
        (binding [*out* *err*]
          (cst/print-cause-trace ex)
          (flush)
          (System/exit 4))))
    ;; Successful run if no other exit.
    (System/exit 0)))
