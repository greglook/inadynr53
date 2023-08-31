(ns dynr53.route53
  "API wrapper code for interacting with Route53."
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [cognitect.aws.client.api :as aws]
    [cognitect.aws.http.cognitect :as http]
    ;; Explicitly load these so graal understands the relationship, since
    ;; the aws lib dynamically loads them at runtime.
    [cognitect.aws.protocols.json]
    [cognitect.aws.protocols.rest]
    [cognitect.aws.protocols.rest-xml]
    [dialog.logger :as log]))


(defn- downcase-keys
  "Convert all map keyword keys into lower-case variants."
  [m]
  (walk/postwalk
    (fn visit
      [x]
      (if (map? x)
        (update-keys x (comp keyword str/lower-case name))
        x))
    m))


(defn- api->record
  "Coerce the capitalized API shape to the local representation."
  [record]
  (-> record
      (downcase-keys)
      (dissoc :resourcerecords)
      (assoc :records (mapv :Value (:ResourceRecords record)))))


(defn- record->api
  "Coerce the local representation of a record set into the capitalized API shape."
  [record]
  {:Name (:name record)
   :Type (:type record)
   :TTL (:ttl record)
   :ResourceRecords (mapv #(array-map :Value %) (:records record))})


(defn- change->api
  "Coerce the local representation of a record set change into the capitalized
  API shape."
  [change]
  (let [action (str/upper-case (name (:action change)))
        record (record->api change)]
    {:Action action
     :ResourceRecordSet record}))


(defn- aws-invoke
  "Shorthand for calling `aws/invoke`, which also throws exceptions."
  [client op request]
  (let [resp (aws/invoke client {:op op, :request request})]
    (when (:cognitect.anomalies/category resp)
      (throw (ex-info (:cognitect.anomalies/message resp "API error")
                      (dissoc resp :cognitect.aws.util/throwable)
                      (:cognitect.aws.util/throwable resp))))
    resp))


(defn new-client
  "Construct a new Route53 client."
  []
  (aws/client {:api :route53, :http-client (http/create)}))


(defn get-hosted-zone
  "Fetch information about the identified hosted zone."
  [client zone-id]
  (-> (aws-invoke client :GetHostedZone {:Id zone-id})
      (:HostedZone)
      (downcase-keys)))


(defn list-resource-record-sets
  "List the resource record sets in a hosted zone."
  [client zone-id]
  ;; TODO: handle pagination
  (let [resp (aws-invoke client :ListResourceRecordSets {:HostedZoneId zone-id})]
    (mapv api->record (:ResourceRecordSets resp))))


(defn change-resource-record-sets!
  "Apply changes to the resource record sets in a zone."
  [client zone-id changes]
  (let [changes (mapv change->api changes)
        resp (aws-invoke client :ChangeResourceRecordSets
                         {:HostedZoneId zone-id
                          :ChangeBatch {:Changes changes
                                        :Comment "dynr53 worker sync"}})
        change-id (get-in resp [:ChangeInfo :Id])
        change-id (if (str/starts-with? change-id "/change/")
                    (subs change-id 8)
                    change-id)]
    (-> (:ChangeInfo resp)
        (downcase-keys)
        (assoc :id change-id))))


(defn get-change
  "Fetch the latest information about the identified change."
  [client change-id]
  (-> (aws-invoke client :GetChange {:Id change-id})
      (:ChangeInfo)
      (downcase-keys)))
