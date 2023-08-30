(ns dynr53.db
  "Simple state tracking for the system."
  (:require
    [clojure.java.io :as io]
    [clojure.edn :as edn])
  (:import
    java.time.Instant))


(defn initialize
  "Return a new empty state database."
  []
  (atom {}))


(defn set-target-address!
  [state hostname address]
  (update-in state [hostname :target]
             assoc
             :address address
             :updated-at (Instant/now)))
