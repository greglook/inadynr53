(ns dynr53.worker
  "Background worker which drives Route53 interactions."
  (:require
    [dialog.logger :as log]
    [dynr53.db :as db])
  (:import
    java.time.Instant))


(defn- worker-loop
  [config db]
  (let [running (volatile! true)]
    (while (and @running (not (Thread/interrupted)))
      (try
        (Thread/sleep 10000)
        (log/info "tick")
        (prn @db)
        ;; TODO: implement
        ;; for each target record in db:
        ;; - check current value in route53
        ;; - if value matches goal, next
        ;; - call route53 to update the resource record set
        ;; - poll for completion
        ,,,
        (catch InterruptedException _
          (vreset! running false))))))


(defn start!
  "Starts a worker thread."
  [config db]
  (log/info "Starting worker thread")
  (doto (Thread. ^Runnable #(worker-loop config db) "dynr53-worker")
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
