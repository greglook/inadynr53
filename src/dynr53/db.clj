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
    :records [,,,]
    :updated-at #inst "2023-08-29T19:50:18Z"}

   :change
   {:id "C..."
    :state "PENDING"
    :changes [,,,]
    :submitted-at #inst "2023-08-29T21:20:51Z"
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


(defn now
  ^Instant
  []
  (Instant/now))


(defn set-zone-info!
  [db zone-name zone-desc]
  (-> db
      (swap! update :zone
             assoc
             :name zone-name
             :desc zone-desc)
      (:zone)))


(defn set-zone-records!
  [db records]
  (-> db
      (swap! update :zone
             assoc
             :records records
             :updated-at (now))
      (:zone)))


(defn set-change!
  [db change-id status changes]
  (-> db
      (swap! assoc :change
             {:id change-id
              :state status
              :changes changes
              :submitted-at (now)
              :checked-at (now)})
      (:change)))


(defn touch-change!
  [db]
  (-> db
      (swap! assoc-in [:change :checked-at] (now))
      (:change)))


(defn apply-change!
  [db]
  (swap! db
         (fn [s]
           (let [change (:change s)
                 changed-records (into {}
                                       (map (juxt (juxt :name :type) identity))
                                       (:changes change))
                 records (into []
                               (map
                                 (fn check-replacement
                                   [extant]
                                   (get changed-records [(:name extant) (:type extant)] extant)))
                               (get-in s [:zone :records]))
                 created (apply disj (set (keys changed-records))
                                (map (juxt :name :type) records))
                 records (into records (map changed-records) created)]
             (-> s
                 (assoc-in [:zone :records] records)
                 (assoc :change nil)))))
  nil)


(defn clear-change!
  [db]
  (swap! db assoc :change nil)
  nil)


(defn set-target-address!
  [db hostname address]
  (-> db
      (swap! update-in [:targets hostname]
             assoc
             :address address
             :set-at (now))
      (get-in [:targets hostname])))
