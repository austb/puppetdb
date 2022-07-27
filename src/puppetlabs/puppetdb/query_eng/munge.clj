(ns puppetlabs.puppetdb.query-eng.munge
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.utils :as utils]))

;; These entities do not use the query engine in engine.clj, their query code lives in the query/ directory.
;; They also need to call into the query engine to compile a filter for their code, so they require engine.clj
;; This means that unlike the facts/catalog/reports entities, engine.clj cannot depend on those files without
;; creating a circular dependency. So instead, these munge functions live in the query engine so that we can handle
;; the entire user query in engine.clg. The disparate locations of these munge functions is less than ideal, but
;; I think it is due to parts of the legacy query engine still residing in the query/ directory rather than query_eng/.
;; In the future it may make sense to move the legacy queries into the query_eng as well to resolve this issue.

(defn- munge-event-count-subject
  "Helper function to transform the event count subject data from the raw format that we get back from the
  database into the more structured format that the API specifies."
  [summarize_by result]
  {:pre [(contains? #{"certname" "resource" "containing_class"} summarize_by)
         (map? result)
         (or
          (contains? result :certname)
          (every? #(contains? result %) [:resource_type :resource_title])
          (contains? result :containing_class))]
   :post [(map? %)
          (not (ks/contains-some % [:certname :resource_type :resource_title :containing_class]))
          (map? (:subject %))
          (= summarize_by (:subject_type %))]}
  (condp = summarize_by
    "certname"          (-> result
                            (assoc :subject_type "certname")
                            (assoc :subject {:title (:certname result)})
                            (dissoc :certname))

    "resource"          (-> result
                            (assoc :subject_type "resource")
                            (assoc :subject {:type (:resource_type result) :title (:resource_title result)})
                            (dissoc :resource_type :resource_title))

    "containing_class"  (-> result
                            (assoc :subject_type "containing_class")
                            (assoc :subject {:title (:containing_class result)})
                            (dissoc :containing_class))))

(defn event-counts-result-row
  "Helper function to transform the event count subject data from the raw format that we get back from the
   database into the more structured format that the API specifies."
  [summarize_by _ _]
  (fn [rows]
    (map (partial munge-event-count-subject summarize_by) rows)))

(defn aggregate-event-counts-result-row
  [_ _]
  (fn [rows]
    (map (partial ks/mapvals #(if (nil? %) 0 %)) rows)))

(defn events-result-row
  "Returns a function that munges the resulting rows ready for final
   presentation."
  [_ _]
  (fn [rows]
    (map (comp #(utils/update-when % [:old_value] json/parse-string)
               #(utils/update-when % [:new_value] json/parse-string))
         rows)))
