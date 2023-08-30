(ns dynr53.worker
  "Background worker which drives Route53 interactions."
  (:require
    [dialog.logger :as log]
    [dynr53.db :as db])
  (:import
    (java.time
      Duration
      Instant)))


(def worker-sleep
  "How long should the worker thread sleep between ticks, in milliseconds."
  10000)


(def ^Duration zone-poll-period
  "How long to wait between fetches of the hosted zone records."
  (Duration/ofHours 1))


(def ^Duration change-poll-period
  "How long to wait between checks on the status of a pending change."
  (Duration/ofMinutes 1))


(defn- monitor-change
  "Monitor an in-progress change request."
  [client change]
  ;; - check timestamp to see if we're due for an update on it
  ;; - if so, call GetChange to see how the batch is doing, log status
  ;; - clear state and update local record knowledge if done
  ,,,)


(defn- monitor-records
  "Monitor the zone records and desired targets."
  [client zone targets]
  (let [zone-id (:id zone)
        ^Instant updated-at (:updated-at zone)
        now (Instant/now)]
    (cond
      ;; If this is the first pass, we'll only have a zone id. Look up the rest
      ;; of the zone info.
      (= [:id] (keys zone))
      (do
        (log/info "Fetching initial information about Hosted Zone" zone-id)
        ;; TODO: call GetHostedZone, update :name
        ,,,)

      ;; Check whether we're due for a record set update.
      (or (nil? updated-at)
          (.isAfter now (.plus updated-at zone-poll-period)))
      (do
        (log/infof "Updating record set for zone %s (%s)" (:name zone) zone-id)
        ;; TODO: call ListResourceRecordSets, update :zone :records
        ,,,)

      ;; Otherwise, check the current record state against desired targets.
      ;; Apply change if needed.
      :else
      (do
        ;; - check all the targets against the zone records and determine any changes that need to be made
        ;; - if changes needed, submit new ChangeResourceRecordSets request
        ,,,))))


(defn- worker-loop
  [db]
  (let [client nil  ; TODO: initialize route53 client
        running (volatile! true)]
    (while (and @running (not (Thread/interrupted)))
      (try
        (Thread/sleep worker-sleep)
        (let [state @db]
          (if-let [change (:change state)]
            (monitor-change client change)
            (monitor-records client (:zone state) (:targets state))))
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
