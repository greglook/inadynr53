(ns dynr53.route53
  "API wrapper code for interacting with Route53."
  (:import
    java.util.Collection
    (software.amazon.awssdk.http.urlconnection
      UrlConnectionHttpClient)
    (software.amazon.awssdk.regions
      Region)
    (software.amazon.awssdk.services.route53
      Route53Client
      Route53ClientBuilder)
    (software.amazon.awssdk.services.route53.model
      Change
      ChangeBatch
      ChangeInfo
      ChangeResourceRecordSetsRequest
      GetChangeRequest
      GetHostedZoneRequest
      HostedZone
      ListResourceRecordSetsRequest
      ResourceRecord
      ResourceRecordSet)))


;; ## Client Construction

(defn new-client
  "Construct a new Route53 client."
  []
  (as-> (Route53Client/builder)
    builder
    ^Route53ClientBuilder
    (.httpClientBuilder builder (UrlConnectionHttpClient/builder))
    ^Route53ClientBuilder
    (.region builder Region/AWS_GLOBAL)
    ^Route53ClientBuilder
    (.build builder)))


;; ## Coercion Functions

(defn- HostedZone->map
  [^HostedZone zone]
  {:id (.id zone)
   :name (.name zone)
   :comment (some-> (.config zone) (.comment))})


(defn- val->ResourceRecord
  ^ResourceRecord
  [value]
  (.. (ResourceRecord/builder)
      (value value)
      (build)))


(defn- ResourceRecord->val
  [^ResourceRecord record]
  (.value record))


(defn- map->ResourceRecordSet
  ^ResourceRecordSet
  [rrset]
  (.. (ResourceRecordSet/builder)
      (name (:name rrset))
      (type ^String (:type rrset))
      (ttl (:ttl rrset))
      (resourceRecords ^Collection (mapv val->ResourceRecord (:records rrset)))
      (build)))


(defn- ResourceRecordSet->map
  [^ResourceRecordSet rrset]
  {:name (.name rrset)
   :type (.typeAsString rrset)
   :ttl (.ttl rrset)
   :records (mapv ResourceRecord->val (.resourceRecords rrset))})


(defn- map->Change
  ^Change
  [change]
  (let [action (case (:action change)
                 :create "CREATE"
                 :upsert "UPSERT")]
    (.. (Change/builder)
        (action ^String action)
        (resourceRecordSet (map->ResourceRecordSet change))
        (build))))


(defn- ChangeInfo->map
  [^ChangeInfo info]
  {:id (.id info)
   :comment (.comment info)
   :status (.statusAsString info)
   :submitted-at (.submittedAt info)})


;; ## API Methods

(defn get-hosted-zone
  "Fetch information about the identified hosted zone."
  [^Route53Client client ^String zone-id]
  (let [req (.. (GetHostedZoneRequest/builder)
                (id zone-id)
                (build))
        resp (.getHostedZone client ^GetHostedZoneRequest req)
        zone (.hostedZone resp)]
    (HostedZone->map zone)))


(defn list-resource-record-sets
  "List the resource record sets in a hosted zone."
  [^Route53Client client ^String zone-id]
  (let [req (.. (ListResourceRecordSetsRequest/builder)
                (hostedZoneId zone-id)
                (build))
        resp (.listResourceRecordSetsPaginator client ^ListResourceRecordSetsRequest req)]
    (mapv ResourceRecordSet->map (.resourceRecordSets resp))))


(defn change-resource-record-sets!
  "Apply changes to the resource record sets in a zone."
  [^Route53Client client ^String zone-id changes]
  (let [batch (.. (ChangeBatch/builder)
                  (comment "dynr53 sync")
                  (changes ^Collection (mapv map->Change changes))
                  (build))
        req (.. (ChangeResourceRecordSetsRequest/builder)
                (hostedZoneId zone-id)
                (changeBatch ^ChangeBatch batch)
                (build))
        resp (.changeResourceRecordSets client ^ChangeResourceRecordSetsRequest req)
        info (.changeInfo resp)]
    (ChangeInfo->map info)))


(defn get-change
  "Fetch the latest information about the identified change."
  [^Route53Client client ^String change-id]
  (let [req (.. (GetChangeRequest/builder)
                (id change-id)
                (build))
        resp (.getChange client ^GetChangeRequest req)
        info (.changeInfo resp)]
    (ChangeInfo->map info)))
