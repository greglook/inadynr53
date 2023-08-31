(ns dynr53.server
  "Server handler implementation."
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [dialog.logger :as log]
    [dynr53.db :as db]
    [org.httpkit.server :as hks])
  (:import
    java.util.Base64))


(defn- path-dispatch
  "Ring middleware which dispatches to sub-handlers by URI path. Returns a 404
  response if none match."
  [path-handlers]
  (fn wrapper
    [req]
    (if-let [handler (get path-handlers (:uri req))]
      (handler req)
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body "Not Found\n"})))


(defn- wrap-logging
  "Ring middleware which will log completed requests."
  [handler]
  (fn wrapper
    [req]
    (let [start (System/nanoTime)
          resp (handler req)]
      (log/infof "[%s] %s %s %s (%.2f ms)"
                 (:remote-addr req "--")
                 (or (:status resp) "---")
                 (if-let [method (:request-method req)]
                   (str/upper-case (name method))
                   "???")
                 (str (:uri req)
                      (when-let [query (:query-string req)]
                        (str "?" query)))
                 (/ (- (System/nanoTime) start) 1e6))
      resp)))


(defn- wrap-error-handler
  "Ring middleware which catches errors and returns a well-formed 500 response."
  [handler]
  (fn wrapper
    [req]
    (try
      (handler req)
      (catch Exception ex
        (log/error ex "Unhandled request error")
        {:status 500
         :headers {"Content-Type" "text/plain"}
         :body (str "Internal Server Error\n" (ex-message ex))}))))


(defn- wrap-method-check
  "Ring middleware which checks that the request is one of the provided
  methods. Returns a 405 response if not."
  [handler allowed]
  (let [allowed (set allowed)]
    (fn wrapper
      [req]
      (if (contains? allowed (:request-method req))
        (handler req)
        {:status 405
         :headers {"Allow" (->> (sort allowed)
                                (map (comp str/upper-case name))
                                (str/join ", "))
                   "Content-Type" "text/plain"}
         :body "Method Not Allowed\n"}))))


(defn- auth-valid?
  "True if the request provides the expected basic auth credentials."
  [expected req]
  (let [auth-header (get-in req [:headers "authorization"])]
    (cond
      ;; No auth header, deny.
      (str/blank? auth-header)
      false

      ;; Not basic auth, dunno.
      (not (str/starts-with? auth-header "Basic "))
      false

      ;; Check auth
      :else
      (-> (Base64/getDecoder)
          (.decode (subs auth-header 6))
          (String.)
          (= expected)))))


(defn- wrap-authentication
  "Ring middleware hich checks that the request is authenticated, if expected
  basic credentials are provided. Returns a 401 response if not."
  [handler basic-auth]
  (if (str/blank? basic-auth)
    handler
    (fn wrapper
      [req]
      (if (auth-valid? basic-auth req)
        (handler req)
        {:status 401
         :headers {"Content-Type" "text/plain"}
         :body "Authentication Required\n"}))))


(defn- parse-query
  "Parse a request query string into a map of string pairs. Repeated entries
  will create a vector."
  [query-string]
  (when-not (str/blank? query-string)
    (reduce
      (fn update-value
        [m pair]
        (let [[k v] (str/split pair #"=" 2)
              v (or v true)
              e (get m k)]
          (assoc m k (cond
                       (coll? e)
                       (conj e v)

                       (string? e)
                       [e v]

                       :else
                       v))))
      {}
      (str/split query-string #"&"))))


(defn- wrap-query-params
  "Ring middleware which parses the query string parameters. Returns a 400
  response if any are missing."
  [handler required]
  (fn wrapper
    [req]
    (let [params (parse-query (:query-string req))
          missing (apply disj (set required) (keys params))]
      (if (seq missing)
        {:status 400
         :headers {"Content-Type" "text/plain"}
         :body (str "Missing required query parameters: "
                    (str/join ", " missing) "\n")}
        (handler (assoc req :parameters params))))))


(defn- set-target-address
  "Handle the request."
  [db req]
  (let [params (:parameters req)
        hostname (get params "hostname")
        address (get params "address")
        zone-name (get-in @db [:zone :name])]
    (cond
      ;; Wait for data to become available.
      (nil? zone-name)
      {:status 503
       :headers {"Content-Type" "text/plain"}
       :body "Waiting for hosted zone information\n"}

      ;; Check that hostname matches zone.
      (not (str/ends-with? (str hostname ".") (str "." zone-name)))
      {:status 409
       :headers {"Content-Type" "text/plain"}
       :body (str "Requested hostname " hostname
                  " does not belong to hosted zone " zone-name "\n")}

      ;; Otherwise set target.
      :else
      (do
        (db/set-target-address! db hostname address)
        {:status 200
         :headers {"Content-Type" "text/plain"}
         :body "OK\n"}))))


(defn- render-state
  "Handle a request to show the current state of the system."
  [db _req]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (str (json/write-str @db) "\n")})


(defn handler
  "Construct a new Ring handler function for the server."
  [config db]
  (->
    (path-dispatch
      {"/dyndns/update"
       (-> (partial set-target-address db)
           (wrap-query-params #{"hostname" "address"})
           (wrap-authentication (:basic-auth config)))

       "/dyndns/state"
       (partial render-state db)})
    (wrap-method-check #{:get})
    (wrap-error-handler)
    (wrap-logging)))


(defn start!
  "Start a running server using the configuration and db given. Returns the
  running server."
  [config db]
  (let [address (:http-address config)
        port (:http-port config)]
    (log/infof "Starting server on %s:%d" address port)
    (hks/run-server
      (handler config db)
      {:ip address
       :port port
       :server-header "dynr53"
       :legacy-return-value? false})))


(defn stop!
  "Stop a running server, waiting for up to the timeout."
  [server timeout-ms]
  (log/info "Shutting down server...")
  (try
    @(hks/server-stop! server {:timeout timeout-ms})
    (catch Exception ex
      (log/error ex "Error while stopping the server")))
  nil)
