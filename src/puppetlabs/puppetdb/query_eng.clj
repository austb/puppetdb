(ns puppetlabs.puppetdb.query-eng
  (:require [clojure.java.jdbc :as sql]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.set :refer [rename-keys]]
            [murphy :refer [with-open!]]
            [puppetlabs.i18n.core :refer [trs tru]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query.aggregate-event-counts :as aggregate-event-counts]
            [puppetlabs.puppetdb.query.events :as events]
            [puppetlabs.puppetdb.query.event-counts :as event-counts]
            [puppetlabs.puppetdb.query-eng.default-reports :as dr]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils :refer [with-log-mdc]]
            [puppetlabs.puppetdb.query-eng.engine :as eng]
            [ring.util.io :as rio]
            [schema.core :as s])
  (:import
   (clojure.lang ExceptionInfo)
   (com.fasterxml.jackson.core JsonParseException)
   (java.io IOException InputStream)
   (org.postgresql.util PGobject PSQLException)
   (org.joda.time Period)))

(defn- regular-query->sql
  [query options]
  (if (:add-agent-report-filter options)
    (let [ast (try
                (dr/maybe-add-agent-report-filter-to-query query)
                (catch ExceptionInfo e
                  (let [data (ex-data e)
                        msg (.getMessage e)]
                    (when (not= ::dr/unrecognized-ast-syntax (:kind data))
                      (log/error e (trs "Unknown exception when processing ast to add report type filter(s)."))
                      (throw e))
                    (log/error e msg)
                    {:type ::failed, :message msg}))
                (catch Exception e
                  (log/error e (trs "Unknown exception when processing ast to add report type filter(s)."))
                  (throw e)))]
      (if (and (map? ast) (= (:type ast) ::failed))
        (throw (ex-info (trs "AST validation failed, but was successfully converted to SQL. Please file a PuppetDB ticket at https://tickets.puppetlabs.com \n{0}"
                             (:message ast))
                        {:kind ::dr/unrecognized-ast-syntax
                         :ast query
                         :sql (eng/compile-query->sql query options)}))
        (eng/compile-query->sql ast options)))
    (eng/compile-query->sql query options)))

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
  return nodes matching the `query`. Also returns the proper munge-fn in the result
  map which the caller must use to convert the result rows. `options` represent
  the data necessary for constructing the proper SQL query such as paging
  paging options, node-purge-ttl, and whether to add the default agent report
  filter. `context` represents information useful for debugging not directly
  involved in compiling a query, such as a universal `query-id` for correlating
  log statements."
  [query version options context]
  {:pre  [((some-fn nil? sequential?) query)]
   :post [(map? %)
          (jdbc/valid-jdbc-query? (:results-query %))
          (or (not (:include_total options))
              (jdbc/valid-jdbc-query? (:count-query %)))]}
  (let [{:keys [origin]} options
        {:keys [warn-experimental log-queries query-id]} context
        _ (when log-queries
            ;; Log origin and AST of incoming query
            (log/infof "PDBQuery:%s:%s"
                       query-id (-> (sorted-map :origin origin :ast query)
                                    json/generate-string)))

        {:keys [remaining-query entity]} (eng/parse-query-context query warn-experimental)
        legacy-query (cond
                      (= :aggregate-event-counts entity)
                      (aggregate-event-counts/query->sql version remaining-query options)

                      (= :event-counts entity)
                      (event-counts/query->sql version remaining-query options)

                      (and (= :events entity) (:distinct_resources options))
                      (events/legacy-query->sql false version remaining-query options)

                      :else nil)
        {:keys [sql-query munge-fn]}
        (if legacy-query
          {:sql-query legacy-query :munge-fn (eng/get-munge-fn entity version options "")}
          (regular-query->sql query options))]
    (when (:log-queries context)
      (let [{[sql & params] :results-query} sql-query]
        ;; Log origin and the generated SQL & its parameters after processing
        (log/infof "PDBQuery:%s:%s"
                   query-id
                   (-> (sorted-map :origin origin
                                   :sql sql
                                   :params (vec params))
                       json/generate-string))))
    (assoc sql-query :munge-fn munge-fn)))

(def use-preferred-streaming-method?
  (->> (or (System/getenv "PDB_USE_DEPRECATED_QUERY_STREAMING_METHOD") "yes")
       (re-matches #"yes|true|1") seq not))

(def query-context-schema
  {:scf-read-db s/Any
   :url-prefix String
   :node-purge-ttl Period
   :add-agent-report-filter Boolean
   (s/optional-key :warn-experimental) Boolean
   (s/optional-key :pretty-print) (s/maybe Boolean)
   (s/optional-key :log-queries) Boolean
   (s/optional-key :query-id) s/Str})

(pls/defn-validated stream-query-result
  "Given a query, and database connection, return a Ring response with the query
   results."
  ([version query options context]
   ;; We default to doall because tests need this for the most part
   (stream-query-result version query options context doall))
  ([version :- s/Keyword
    query
    options
    context :- query-context-schema
    row-fn]
   ;; For now, generate the ids here; perhaps later, higher up
   (assert (not (:query-id context)))
   (let [query-id (str (java.util.UUID/randomUUID))
         context (assoc context :query-id query-id)
         origin (:origin options)]
     (with-log-mdc ["pdb-query-id" query-id
                    "pdb-query-origin" origin]
       (let [{:keys [scf-read-db]} context
             f #(let [{:keys [results-query munge-fn]}
                      (query->sql query version
                                  (merge options
                                         (select-keys context [:node-purge-ttl :add-agent-report-filter]))
                                  (select-keys context [:warn-experimental :log-queries :query-id]))]
                  (jdbc/call-with-array-converted-query-rows results-query
                                                             (comp row-fn munge-fn))) ]
           (if use-preferred-streaming-method?
             (jdbc/with-db-connection scf-read-db (jdbc/with-db-transaction [] (f)))
             (jdbc/with-transacted-connection scf-read-db (f))))))))

;; Do we still need this, i.e. do we need the pass-through, and the
;; strict selectivity in the caller below?
(defn- coerce-from-json
  "Parses obj as JSON if it's a string/stream/reader, otherwise
  returns obj."
  [obj]
  (cond
    (string? obj) (json/parse-strict obj true)
    (instance? java.io.Reader obj) (json/parse obj true)
    (instance? java.io.InputStream obj) (json/parse obj true)
    :else obj))

(defn user-query->engine-query
  [_version query-map options]
  (let [query (:query query-map)

        ;; TODO; this is doing extra work identifying the entity, etc
        {:keys [paging-clauses]}
        (eng/parse-query-context query false)

        paging-options (some-> paging-clauses
                               (rename-keys {:order-by :order_by})
                               (update :order_by paging/munge-query-ordering)
                               utils/strip-nil-values)
        query-options (->> (dissoc query-map :query)
                           utils/strip-nil-values
                           (merge {:limit nil :offset nil :order_by nil}
                                  options
                                  paging-options))]
    ;; TODO query here still has paging clauses embedded as well, is that correct?
    {:query query :query-options query-options}))

(defn- generated-stream
  "Creates an InputStream connected to an OutputStream.  Runs (f
  out-stream) in a future, and returns the input stream after
  arranging for any exceptions thrown by f to be rethrown from by
  either the InputStream read or close methods."
  [f]
  (let [err (atom nil)
        with-ex-forwarding (fn [action]
                             (when-let [ex @err] (throw ex))
                             (let [result (action)]
                               (when-let [ex @err] (throw ex))
                               result))
        stream (rio/piped-input-stream
                (fn [out]
                  (try
                    (f out)
                    (catch Throwable ex
                      (reset! err ex)))))]
    (proxy [InputStream] []
      (available [] (.available stream))
      (close [] (with-ex-forwarding #(.close stream)))
      (mark [readlimit] (.mark stream readlimit))
      (markSupported [] (.markSupported stream))
      (read
        ([] (with-ex-forwarding #(.read stream)))
        ([b] (with-ex-forwarding #(.read stream b)))
        ([b off len] (with-ex-forwarding #(.read stream b off len))))
      (reset [] (.reset stream))
      (skip [n] (.skip stream n)))))

(defn- body-stream
  "Returns a map whose :stream is an InputStream that produces (via a
  future) the results of the query as JSON, and whose :status is a
  promise to which nil or {:count n} will be delivered after the first
  row is retrieved, or to which {:error exception} will be delivered
  if an exception happens before then.  An exception thrown by the
  future after that point will produce an exception from the next call
  to the InputStream read or close methods."
  [db query version query-options
   {:keys [pretty-print query-id] :as context}]
  ;; Client disconnects present as generic IOExceptions from the
  ;; output writer (via stream-json), and we just log them at debug
  ;; level.  For now, after the first row, there's nothing we can do
  ;; to signal an error to the client making the query, other than
  ;; halting transmission and closing the connection, which happens
  ;; when the InputStream we return throws from its read or close
  ;; methods.  To provide more meaningful errors to the client, we
  ;; could add a new streaming protocol, i.e. response elements could
  ;; be either an entity map or some status (timeout, error, ...)
  ;; indicator.  We could also consider chunked transfer encoding with
  ;; trailing headers, *if* clients support is good enough.
  ;;
  ;; produce-streaming-body blocks until status is delivered, so
  ;; ensure it always is.
  (let [status (promise)
        quiet-exit (Exception. "private singleton escape exception escaped")
        stream (generated-stream
                ;; Runs in a future
                (fn [out]
                  (with-log-mdc ["pdb-query-id" query-id
                                 "pdb-query-origin" (:origin query-options)]
                    (with-open! [out (io/writer out :encoding "UTF-8")]
                      (try
                        (jdbc/with-db-connection db
                          (jdbc/with-db-transaction []
                            (let [{:keys [results-query count-query munge-fn]}
                                  (query->sql query version query-options context)
                                  st (when count-query
                                       {:count (jdbc/get-result-count count-query)})
                                  stream-row (fn [row]
                                               (let [r (munge-fn row)]
                                                 (when-not (instance? PGobject r)
                                                   (first r))
                                                 (when-not (realized? status)
                                                   (deliver status st))
                                                 (try
                                                   (http/stream-json r out pretty-print)
                                                   (catch IOException ex
                                                     (log/debug ex (trs "Unable to stream response: {0}"
                                                                        (.getMessage ex)))
                                                     (throw quiet-exit)))))]
                              (jdbc/call-with-array-converted-query-rows results-query
                                                                         stream-row)
                              (when-not (realized? status)
                                (deliver status st)))))
                        (catch Exception ex
                          ;; If it's an exit, we've already handled it.
                          (when-not (identical? quiet-exit ex)
                            (if (realized? status)
                              (throw ex)
                              (deliver status {:error ex}))))
                        (catch Throwable ex
                          (if (realized? status)
                            (do
                              (log/error ex (trs "Query streaming failed: {0} {1}"
                                                 query query-options))
                              (throw ex))
                            (deliver status {:error ex}))))))))]
    {:status status
     :stream stream}))

(defn preferred-produce-streaming-body
  [version query-map context]
  (let [{:keys [scf-read-db _url-prefix query-id]} context
        query-config (select-keys context [:node-purge-ttl :add-agent-report-filter])
        {:keys [query query-options]}
        (user-query->engine-query version query-map query-config)]
    (try
      (let [stream-ctx (select-keys context [:log-queries :pretty-print :query-id])
            {:keys [status stream]} (body-stream scf-read-db
                                                 query
                                                 version
                                                 query-options
                                                 stream-ctx)
            {:keys [count error]} @status]
        (when error
          (throw error))
        (cond-> (http/json-response* stream)
          count (http/add-headers {:count count})))
      (catch JsonParseException ex
        (log/error ex (trs "Unparsable query: {0} {1} {2}" query-id query query-options))
        (http/error-response ex))
      (catch IllegalArgumentException ex ;; thrown by (at least) munge-fn
        (log/error ex (trs "Invalid query: {0} {1} {2}" query-id query query-options))
        (http/error-response ex))
      (catch PSQLException ex
        (when-not (= (.getSQLState ex) (jdbc/sql-state :invalid-regular-expression))
          (throw ex))
        (do
          (log/debug ex (trs "Invalid query regex: {0} {1} {2}" query-id query query-options))
          (http/error-response ex))))))

;; for testing via with-redefs
(def munge-fn-hook identity)

(defn- deprecated-produce-streaming-body
  [version query-map context]
  (let [{:keys [scf-read-db _url-prefix pretty-print query-id]} context
        query-config (select-keys context [:node-purge-ttl :add-agent-report-filter])
        {:keys [query query-options]}
        (user-query->engine-query version query-map query-config)
        origin (:origin query-map)]
    (try
      (jdbc/with-transacted-connection scf-read-db
        (let [{:keys [results-query count-query munge-fn]}
              (-> query
                  coerce-from-json
                  (query->sql version query-options
                              ;; TODO this is likely removing necessary parameters warn-experimental
                              (select-keys context [:log-queries :query-id])))
              query-error (promise)
              resp (http/streamed-response
                    buffer
                    (with-log-mdc ["pdb-query-id" query-id
                                   "pdb-query-origin" origin]
                      (try (jdbc/with-transacted-connection scf-read-db
                             (jdbc/call-with-array-converted-query-rows
                              results-query
                              (comp #(http/stream-json % buffer pretty-print)
                                    #(do (when-not (instance? PGobject %)
                                           (first %))
                                         (deliver query-error nil) %)
                                    (munge-fn-hook munge-fn))))
                           ;; catch throwable to make sure any trouble is forwarded to the
                           ;; query-error promise below. If something throws and is not passed
                           ;; along the deref will block indefinitely.
                           (catch Throwable e
                             (deliver query-error e)))))]
          (if @query-error
            (throw @query-error)
            (cond-> (http/json-response* resp)
                    count-query (http/add-headers {:count (jdbc/get-result-count count-query)})))))
      (catch com.fasterxml.jackson.core.JsonParseException e
        (log/error
         e
         (trs "Error executing query ''{0}'' with query options ''{1}''. Returning a 400 error code."
              query query-options))
        (http/error-response e))
      (catch IllegalArgumentException e
        (log/error
         e
         (trs
          "Error executing query ''{0}'' with query options ''{1}''. Returning a 400 error code."
          query query-options))
        (http/error-response e))
      (catch org.postgresql.util.PSQLException e
        (if (= (.getSQLState e) (jdbc/sql-state :invalid-regular-expression))
          (do (log/debug e (trs "Caught PSQL processing exception"))
              (http/error-response (.getMessage e)))
          (throw e))))))

(pls/defn-validated produce-streaming-body
  "Given a query, and database connection, return a Ring response with
   the query results. query-map is a clojure map of the form
   {:query ['=','certname','foo'] :order_by [{'field':'foo'}]...}
   If the query can't be parsed, a 400 is returned."
  [version :- s/Keyword
   query-map
   query-uuid :- s/Str
   context :- query-context-schema]
  (let [context (assoc context :query-id query-uuid)]
    (with-log-mdc ["pdb-query-id" query-uuid
                   "pdb-query-origin" (:origin query-map)]
      (if use-preferred-streaming-method?
        (preferred-produce-streaming-body version query-map context)
        (deprecated-produce-streaming-body version query-map context)))))

(pls/defn-validated object-exists? :- s/Bool
  "Returns true if an object exists."
  [type :- s/Keyword
   id :- s/Str]
  (let [check-sql (case type
                    :catalog "SELECT 1
                              FROM CATALOGS
                              where catalogs.certname=?"
                    :node "SELECT 1
                           FROM certnames
                           WHERE certname=? "
                    :report (str "SELECT 1
                                  FROM reports
                                  WHERE " (sutils/sql-hash-as-str "hash") "=?
                                  LIMIT 1")
                    :environment "SELECT 1
                                  FROM environments
                                  WHERE environment=?"
                    :producer "SELECT 1
                                  FROM producers
                                  WHERE name=?"
                    :factset "SELECT 1
                              FROM certnames
                              INNER JOIN factsets
                              ON factsets.certname = certnames.certname
                              WHERE certnames.certname=?")]
    (jdbc/query-with-resultset [check-sql id]
                               (comp boolean seq sql/result-set-seq))))
