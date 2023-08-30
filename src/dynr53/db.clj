(ns dynr53.db
  "Simple state tracking for the system."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io])
  (:import
    java.time.Instant))


(defn initialize
  "Return a new empty state database."
  []
  (atom {}))


(defn set-target-address!
  [state hostname address]
  (swap! state
         update-in [hostname :target]
         assoc
         :address address
         :updated-at (Instant/now)))
