(ns dynr53.db
  "Simple state tracking for the system."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [dialog.logger :as log])
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

   :sources
   {"path/to/file.txt"
    {:targets #{"foo.example.com"}
     :read-at #inst "2023-08-29T21:05:38"}}

   :targets
   {"foo.example.com"
    {:address "123.45.67.89"
     :set-at #inst "2023-08-29T20:04:32Z"}}})


;; ## State Persistence

(defmethod print-method Instant
  [inst writer]
  (print-method (tagged-literal 'inst (str inst)) writer))


(def ^:private target-state-mutex
  "Object to lock on to prevent concurrent writes to the state file."
  (Object.))


(defn- load-target-state
  "Load the saved target state from a file. Returns the loaded data on success,
  or an empty map otherwise."
  [state-dir]
  (let [targets-file (io/file state-dir "targets.edn")]
    (if (.exists targets-file)
      ;; Try to read targets from state file.
      (try
        (log/info "Loading target state from" (str targets-file))
        (let [targets (edn/read-string
                        {:readers {'inst #(Instant/parse %)}}
                        (locking target-state-mutex
                          (slurp targets-file)))]
          (when-not (map? targets)
            (throw (IllegalStateException.
                     (str "Unexpected value type in targets state file: "
                          (pr-str targets)))))
          targets)
        (catch Exception ex
          (log/error ex "Failed to load target state from file"
                     (str targets-file))
          {}))
      ;; No state file.
      {})))


(defn- save-target-state!
  "Persist the target state to a file. Returns true on success, false
  otherwise."
  [state-dir targets]
  (let [state-dir (io/file state-dir)
        targets-file (io/file state-dir "targets.edn")]
    (if (.isDirectory state-dir)
      (try
        (let [rendered (prn-str targets)]
          (locking target-state-mutex
            (spit targets-file rendered))
          true)
        (catch Exception ex
          (log/error ex "Failed to save target state to file"
                     (str targets-file))
          false))
      ;; Not a directory to save in.
      false)))


;; ## Initialization

(defn initialize
  "Return a new empty state database."
  [config]
  (let [state-dir (:state-dir config)
        targets-source (:targets-file config)
        db (atom {:zone {:id (:zone-id config)}
                  :change nil
                  :sources (if targets-source
                             {targets-source {}}
                             {})
                  :targets (if state-dir
                             (load-target-state state-dir)
                             {})})]
    (when state-dir
      (alter-meta! db assoc ::state-dir state-dir))
    db))


(defn now
  ^Instant
  []
  (Instant/now))


;; ## State Functions

(defn set-zone-info!
  [db zone-name zone-comment]
  (-> db
      (swap! update :zone
             assoc
             :name zone-name
             :comment zone-comment)
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


(defn- changed-records
  "Apply the updates in `changes` to the existing `records`, returning a new
  collection with any conflicting records replaced."
  [records changes]
  (let [changed (into {}
                      (map (juxt (juxt :name :type) #(dissoc % :action)))
                      changes)
        created (apply disj (set (keys changed))
                       (map (juxt :name :type) records))]
    (-> []
        (into (map (fn replace-extant
                     [extant]
                     (get changed [(:name extant) (:type extant)] extant)))
              records)
        (into (map changed) created))))


(defn apply-change!
  [db]
  (swap! db
         (fn apply-changes
           [s]
           (let [records (get-in s [:zone :records])
                 changes (get-in s [:change :changes])]
             (-> s
                 (assoc-in [:zone :records] (changed-records records changes))
                 (assoc :change nil)))))
  nil)


(defn clear-change!
  [db]
  (swap! db assoc :change nil)
  nil)


(defn touch-source!
  [db path targets]
  (let [source {:targets (set targets)
                :read-at (now)}]
    (swap! db assoc-in [:sources path] source)
    source))


(defn set-target-address!
  [db hostname address]
  (let [targets (-> db
                    (swap! update-in [:targets hostname]
                           assoc
                           :address address
                           :set-at (now))
                    (:targets))]
    (when-let [state-dir (::state-dir (meta db))]
      (save-target-state! state-dir targets))
    (get targets hostname)))
