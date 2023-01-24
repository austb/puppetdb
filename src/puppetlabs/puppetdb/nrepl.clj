(ns puppetlabs.puppetdb.nrepl
  (:require [puppetlabs.puppetdb.cli.services :refer [init-with-db]]
            [puppetlabs.puppetdb.jdbc :refer [with-db-connection with-db-transaction] :as jdbc]
            [puppetlabs.puppetdb.query-eng :as qeng]
            [puppetlabs.puppetdb.query-eng.engine :as eng]
            [puppetlabs.puppetdb.scf.storage :refer [init-storage-metrics]]))

;; TODO duplicated from testutils/db.clj
(defn valid-sql-id? [id]
  (re-matches #"[a-zA-Z][a-zA-Z0-9_]*" id))

;; TODO much of this is nearly identical to testutils db function with different names

(def pdbbox-env
  (let [user (or (System/getenv "PDB_DB_USER") "pdb_test")
        migrator (or (System/getenv "PDB_DB_MIGRATOR") "pdb_test_migrator")
        admin (or (System/getenv "PDB_DB_ADMIN") "pdb_test_admin")
        read-only (or (System/getenv "PDB_DB_READ") "pdb_test_read")]
    ;; Since we're going to use these in raw SQL later (i.e. not via ?).
    (doseq [[who name] [[:user user] [:migrator migrator] [:admin admin]]]
      (when-not (valid-sql-id? name)
        (println (format "Invalid test %s name %s" who (pr-str name)))))
    {:host (or (System/getenv "PDB_DB_HOST") "127.0.0.1")
     :port (or (System/getenv "PDB_DB_PORT") "5432")
     :user {:name user
            :password (or (System/getenv "PDB_DB_USER_PASSWORD") "puppetdb")}
     :read {:name read-only
            :password (or (System/getenv "PDB_DB_READ_PASSWORD") "puppetdb_read")}
     :migrator {:name migrator
                :password (or (System/getenv "PDB_DB_MIGRATOR_PASSWORD") "puppetdb_migrator")} }))

(defn routine-db-config
  "Returns a config suitable for routine pdb operations (i.e. not
  admin/superuser operations)."
  [database]
  {:classname "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname (format "//%s:%s/%s" (:host pdbbox-env) (:port pdbbox-env) database)
   :user (get-in pdbbox-env [:user :name])
   :username (get-in pdbbox-env [:user :name])
   :password (get-in pdbbox-env [:user :password])
   :migrator-username (get-in pdbbox-env [:migrator :name])
   :migrator-password (get-in pdbbox-env [:migrator :password])
   :maximum-pool-size 5})

(defn read-db-config
  "Returns a config suitable for routine pdb operations (i.e. not
  admin/superuser operations)."
  [database]
  {:classname "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname (format "//%s:%s/%s" (:host pdbbox-env) (:port pdbbox-env) database)
   :user (get-in pdbbox-env [:read :name])
   :username (get-in pdbbox-env [:read :name])
   :password (get-in pdbbox-env [:read :password])
   :maximum-pool-size 5})

(def db-name "puppetdb")

(defn query->sql
  ([query]
   (query->sql query {}))
  ([query options]
   (let [{:keys [remaining-query entity]} (eng/parse-query-context query false)]
     (:results-query
      (eng/compile-user-query->sql (qeng/get-query-rec entity options)
                                   remaining-query)))))

(defn ensure-db
  []
  (init-with-db db-name
                (routine-db-config db-name)
                (:username (read-db-config db-name))))

(comment "run migrations if necessary"
(ensure-db))

(comment "compile queries to sql"
(query->sql ["from" "nodes" ["extract" "certname"]])


(with-db-connection (read-db-config db-name)
  (do
    (time (query->sql ["from" "nodes"
                       ["extract" ["certname"]
                        ["in" "certname" ["array" (into [] (repeat 48000 "foo"))]]]]))
    ;; avoid printing large query SQL to repl
    nil))

(query->sql ["from" "reports" ["extract" ["certname"] ["in" "certname" ["from" "nodes" ["extract" "certname" ["=" "facts_timestamp" "2021-01-23T12:00:00Z"]]]]]]))

(defn query
  [query]
  (with-db
    (let [{:keys [entity]} (eng/parse-query-context query false)
          sql (query->sql query)
          munge-fn (qeng/get-munge-fn entity 4 {} "")
          row-fn doall]
      ;; Create a connection with the read user
      (with-db-connection (read-db-config db-name)
        (with-db-transaction {}
          (jdbc/call-with-array-converted-query-rows sql
                                                     (comp row-fn munge-fn)))))))

(comment "execute queries on the database"
(query ["from" "nodes" ["extract" ["certname" "deactivated" "expired"]]]))
