(ns dynr53.config
  "Service configuration via environment variables, primarily."
  (:require
    [clojure.string :as str]))


(def defaults
  "Default configuration values."
  {:http-address "0.0.0.0"
   :http-port 8300})


(def variables
  "Configuration variable mappings."
  [{:key :http-address
    :env "DYNR53_HTTP_ADDRESS"
    :desc "IP address to bind the HTTP server to"
    :validate (complement str/blank?)}
   {:key :http-port
    :env "DYNR53_HTTP_PORT"
    :desc "TCP port to bind the HTTP server to"
    :parse parse-long
    :validate pos-int?}
   {:key :basic-auth
    :env "DYNR53_BASIC_AUTH"
    :desc "Require clients to present this 'user:pass' using basic auth"
    :validate (partial re-matches #"[^ :]+:[^ :]+")}
   {:key :zone-id
    :env "DYNR53_ZONE_ID"
    :desc "Route53 Hosted Zone identifier to apply updates to"
    :validate (complement str/blank?)}])


(defn load-config
  "Read configuration values from the environment and defaults, returning a map
  with keyword/value pairs."
  []
  (reduce
    (fn set-variable
      [config variable]
      (if-let [raw (System/getenv (:env variable))]
        (let [parser (:parse variable identity)
              valid? (:validate variable any?)
              value (parser raw)]
          (when-not (valid? value)
            (throw (RuntimeException.
                     (str "Value " (pr-str raw) " is not a valid value for "
                          (:env variable)))))
          (assoc config (:key variable) value))
        config))
    defaults
    variables))
