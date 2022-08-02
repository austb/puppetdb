(ns puppetlabs.puppetdb.honeysql
  "Some generic HoneySQL extensions, candidates for re-usability and
  potential upstream submission."
  (:require [honeysql.types :as t])
  (:import [honeysql.types SqlRaw]))

;; SCHEMA

;; COMMMON DIRECT SQL FUNCTIONS

(defn coalesce
  "coalesce(arg, ...) sql function"
  [& args]
  (into [:coalesce] args))

(defn scast
  "cast(source AS target) sql function"
  [source
   target]
  [:cast source target])

(defn json-agg
  "json_agg(expr) sql function"
  [expr]
  [:json_agg expr])

(defn row-to-json
  "row_to_json(record) sql function"
  [record]
  [[:row_to_json record]])

;; Misc honeysql functions

(defn raw? [x]
  (instance? honeysql.types.SqlRaw x))

(defn extract-sql
  "Returns a keyworidzed version of the SQL string if `k` is a SqlRaw, otherwise returns `s`"
  [s]
  (if-let [^SqlRaw raw-sql (and (raw? s) s)]
    (keyword (.s raw-sql))
    s))

(defn extract-sql-v2
  "Returns a keyworidzed version of the SQL string if `k` is a SqlRaw, otherwise returns `s`"
  [s]
  (if (keyword? s)
    s
    (keyword (second s)))) ;; assumes it is a hsql v2 raw

(defn sqlraw->str
  [^SqlRaw raw-sql]
  (.s raw-sql))

;; NEW OPERATORS

; Custom formatter for PostgreSQL's ~ operator
; (defmethod hfmt/fn-handler "~" [_ a b & more]
;   (if (seq more)
;     (apply hfmt/expand-binary-ops "~" a b more)
;     (str (hfmt/to-sql a) " ~ " (hfmt/to-sql b))))
