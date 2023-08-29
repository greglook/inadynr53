(ns dynr53.server
  "Server handler implementation."
  (:require
    [clojure.pprint :as pp]
    [clojure.string :as str]
    [dialog.logger :as log])
  (:import
    java.util.Base64))


(defn- auth-valid?
  "True if the request provides the expected basic auth credentials."
  [expected req]
  (let [auth-header (get-in req [:headers "authorization"])]
    (cond
      ;; No auth provided, so anything works.
      (nil? expected)
      true

      ;; No auth header, deny.
      (str/blank? auth-header)
      false

      ;; Not basic auth, dunno.
      (not (str/starts-with? auth-header "Basic "))
      false

      ;; Check auth
      :else
      (let [[user pass] (-> (Base64/getDecoder)
                            (.decode (subs auth-header 6))
                            (String.)
                            (str/split #":" 2))]
        (and (= user (:user expected))
             (= pass (:pass expected)))))))


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


(defn- check-request
  "Check the request parameters. Returns a response on error, or nil if the
  request should be handled."
  [expected-auth req]
  (cond
    (not= "/dyndns/update" (:uri req))
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "Not Found\n"}

    (not= :get (:request-method req))
    {:status 405
     :headers {"Allow" "GET"
               "Content-Type" "text/plain"}
     :body "Method Not Allowed\n"}

    (not (auth-valid? expected-auth req))
    {:status 401
     :headers {"Content-Type" "text/plain"}
     :body "Authentication Required\n"}))


(defn- do-the-thing
  "Handle the request."
  [req]
  (let [params (parse-query (:query-string req))]
    (prn params)
    ;; TODO: Do the thing
    {:status 501
     :headers {"Content-Type" "text/plain"}
     :body "NYI"}))


(defn handler
  [req]
  (let [start (System/nanoTime)
        resp (or (check-request {:user "local", :pass "guest"} req)
                 (do-the-thing req))
        elapsed (/ (- (System/nanoTime) start) 1e6)]
    (log/infof "[%s] %s %s %s (%.2f ms)"
               (:remote-addr req "--")
               (or (:status resp) "---")
               (if-let [method (:request-method req)]
                 (str/upper-case (name method))
                 "???")
               (str (:uri req)
                    (when-let [query (:query-string req)]
                      (str "?" query)))
               elapsed)
    resp))
