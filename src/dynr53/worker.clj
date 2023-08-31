(ns dynr53.worker
  "Background worker which drives Route53 interactions."
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
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
        ^Instant updated-at (:updated-at zone)
        now (db/now)]
    (cond
      ;; If this is the first pass, we'll only have a zone id. Look up the rest
      ;; of the zone info.
      (= [:id] (keys zone))
      (let [_ (log/info "Fetching initial information about Hosted Zone" zone-id)
            resp (r53/get-hosted-zone route53 zone-id)
            zone-name (:name resp)
            zone-desc (get-in resp [:config :comment])]
        (db/set-zone-info! db zone-name zone-desc))

      ;; Check whether we're due for a record set update.
      (or (nil? updated-at)
          (.isAfter now (.plus updated-at zone-poll-period)))
      (let [_ (log/infof "Updating record set for zone %s (%s)" (:name zone) zone-id)
            records (r53/list-resource-record-sets route53 zone-id)]
        (db/set-zone-records! db records))

      ;; Otherwise, check the current record state against desired targets.
      ;; Apply change if needed.
      :else
      (let [changes (into []
                          (keep (fn check-sync
                                  [[hostname target]]
                                  (let [record-name (str hostname ".")
                                        record-set (find-record record-name "A" (:records zone))]
                                    (when-not (= [(:address target)] (:records record-set))
                                      {:action (if record-set :upsert :create)
                                       :name record-name
                                       :type "A"
                                       :ttl 300
                                       :records [(:address target)]}))))
                          targets)]
        (if (seq changes)
          (let [_ (log/info "Applying changes to record sets:"
                            (->> changes
                                 (map #(str (:action %) " " (:name %)))
                                 (str/join ", ")))
                resp (r53/change-resource-record-sets! route53 zone-id changes)]
            (db/set-change! db (:id resp) (:status resp) changes))
          ;; In sync
          (log/debug "Zone records and targets are in sync"))))))


(defn- worker-loop
  [db]
  (let [route53 (r53/new-client)
        running (volatile! true)]
    (while (and @running (not (Thread/interrupted)))
      (try
        (Thread/sleep (.toMillis worker-sleep))
        (let [state @db]
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
  (doto (Thread. ^Runnable #(worker-loop db) "dynr53-worker")
    (.setDaemon true)
    (.start)))


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
