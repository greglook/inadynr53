(ns dynr53.worker
  "Background worker which drives Route53 interactions."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [dialog.logger :as log]
    [dynr53.db :as db]
    [dynr53.route53 :as r53])
  (:import
    (java.time
      Duration
      Instant)))


(def ^Duration worker-sleep
  "How long should the worker thread sleep between ticks."
  (Duration/ofSeconds 10))


(def ^Duration zone-poll-period
  "How long to wait between fetches of the hosted zone records."
  (Duration/ofHours 1))


(defn- record-match?
  "True if the record is for the given name and type."
  [record-name record-type record]
  (and (= record-name (:name record))
       (= record-type (:type record))))


(defn- find-record
  "Return the first record in `records` matching the given name and type."
  [record-name record-type records]
  (reduce
    (fn check-record
      [_ record]
      (when (record-match? record-name record-type record)
        (reduced record)))
    nil
    records))


(defn- compute-changes
  "Determine the changes necessary to align the record sets with the targets."
  [records targets]
  (into []
        (keep
          (fn check-sync
            [[hostname target]]
            (let [record-name (str hostname ".")
                  record-set (find-record record-name "A" records)
                  current-records (:records record-set)]
              (when-not (= current-records [(:address target)])
                (->
                  {:action (if record-set :upsert :create)
                   :name record-name
                   :type "A"
                   :ttl 300
                   :records [(:address target)]}
                  (vary-meta assoc ::prev-records current-records))))))
        targets))


(defn- format-change
  "Return a short string explaining a change from compute-changes."
  [change]
  (str (name (:action change)) " " (:name change)
       (when-let [records (::prev-records (meta change))]
         (str " from "
              (if (= 1 (count records))
                (first records)
                (print-str records))))
       (when-let [records (:records change)]
         (str " to "
              (if (= 1 (count records))
                (first records)
                (print-str records))))))


(defn- initialize-zone
  "Fetch initial data about the configured hosted zone."
  [db route53]
  (let [zone-id (get-in @db [:zone :id])]
    (log/info "Fetching initial information for hosted zone" zone-id)
    (let [zone (r53/get-hosted-zone route53 zone-id)]
      (db/set-zone-info! db (:name zone) (:comment zone)))))


(defn- monitor-change
  "Monitor an in-progress change request."
  [db route53 change]
  (let [change-id (:id change)
        _ (log/debug "Checking status of change" change-id)
        resp (r53/get-change route53 change-id)
        status (:status resp)]
    (case status
      "PENDING"
      (do
        (log/info "Change" change-id "is still PENDING")
        (db/touch-change! db))

      "INSYNC"
      (do
        (log/info "Change" change-id "is INSYNC")
        (db/apply-change! db))

      (do
        (log/warn "Unrecognized change status:" status)
        (db/clear-change! db)))))


(defn- monitor-records
  "Monitor the zone records and desired targets."
  [db route53 zone targets]
  (let [zone-id (:id zone)
        ^Instant updated-at (:updated-at zone)]
    (if (or (nil? updated-at)
            (.isAfter (db/now)
                      (.plus updated-at zone-poll-period)))
      ;; Update the knowledge of records in the zone.
      (let [_ (log/debugf "Updating record set for zone %s (%s)" (:name zone) zone-id)
            records (r53/list-resource-record-sets route53 zone-id)]
        (db/set-zone-records! db records))
      ;; Otherwise, check the current record state against desired targets.
      ;; Apply change if needed.
      (let [changes (compute-changes (:records zone) targets)]
        (if (seq changes)
          (let [_ (log/info "Applying changes to record sets:"
                            (str/join ", " (map format-change changes)))
                resp (r53/change-resource-record-sets! route53 zone-id changes)]
            (db/set-change! db (:id resp) (:status resp) changes))
          ;; In sync
          (log/debug "Zone records and targets are in sync"))))))


(defn- check-source-files
  "Try reading targets from the source files if they have been updated."
  [db]
  (doseq [[path state] (:sources @db)]
    (let [file (io/file path)]
      (if (.exists file)
        (let [last-read (:read-at state)
              last-modified (Instant/ofEpochMilli (.lastModified file))]
          (when (or (nil? last-read) (.isAfter last-modified last-read))
            (log/info "Reading targets from file" path
                      (if last-read
                        (str "(modified since" last-read ")")
                        "(first load)"))
            (let [targets (->> (slurp file)
                               (str/split-lines)
                               (remove str/blank?)
                               (map #(str/split % #"\t"))
                               (into {}))]
              (db/touch-source! db path (keys targets))
              (doseq [[hostname address] targets]
                (db/set-target-address! db hostname address)))))
        ;; No file exists
        (log/debug "Skipping source file" path "which does not exist")))))


(defn- worker-loop
  [db route53]
  (let [running (volatile! true)]
    (initialize-zone db route53)
    (while (and @running (not (Thread/interrupted)))
      (try
        (Thread/sleep (.toMillis worker-sleep))
        (let [state @db]
          (check-source-files db)
          (if-let [change (:change state)]
            (monitor-change db route53 change)
            (monitor-records db route53 (:zone state) (:targets state))))
        (catch InterruptedException _
          (vreset! running false))
        (catch Exception ex
          (log/error ex "Unhandled exception during worker tick"))))))


(defn start!
  "Starts a worker thread."
  [db]
  (log/info "Starting worker thread")
  (let [route53 (r53/new-client)]
    (doto (Thread. ^Runnable #(worker-loop db route53) "dynr53-worker")
      (.setDaemon true)
      (.start))))


(defn stop!
  "Stops a worker thread, waiting up to the specified timeout."
  [^Thread worker timeout-ms]
  (log/info "Stopping worker...")
  (try
    (.interrupt worker)
    (.join worker timeout-ms)
    (when (.isAlive worker)
      (log/warn "Worker thread still alive after interrupt!"))
    (catch Exception ex
      (log/error ex "Error while stoping the worker")))
  nil)
