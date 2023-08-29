(ns dynr53.server
  "Server handler implementation."
  (:require
    [clojure.pprint :as pp]
    [clojure.string :as str]
    [dialog.logger :as log]))


(defn handler
  [req]
  (log/infof "[%s] %s %s"
             (:remote-addr req "--")
             (if-let [method (:request-method req)]
               (str/upper-case (name method))
               "???")
             (str (:uri req)
                  (when-let [query (:query-string req)]
                    (str "?" query))))
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (with-out-str (pp/pprint req))})
