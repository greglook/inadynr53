(ns dynr53.db
  "Simple state tracking for the system."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io])
  (:import
    java.time.Instant))


;; System state looks like this:
(comment
  {:zone
   {:id "Z..."
    :name "example.com"
    :records {,,,}
    :updated-at #inst "2023-08-29T19:50:18Z"}

   :change
   {:id "C..."
    :state :pending
    :updates {,,,}
    :started-at #inst "2023-08-29T21:20:51Z"
    :checked-at #inst "2023-08-29T21:22:01Z"}

   :targets
   {"foo.example.com"
    {:address "123.45.67.89"
     :set-at #inst "2023-08-29T20:04:32Z"}}})


(defn initialize
  "Return a new empty state database."
  [config]
  (atom {:zone {:id (:zone-id config)}
         :change nil
         :targets {}}))


(defn set-target-address!
  [state hostname address]
  (swap! state
         update-in [hostname :target]
         assoc
         :address address
         :set-at (Instant/now)))
