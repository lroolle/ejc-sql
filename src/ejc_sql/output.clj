;;; output.clj -- Output & formatting clojure functions for ejc-sql.

;;; Copyright © 2013-2019 - Kostafey <kostafey@gmail.com>

;;; This program is free software; you can redistribute it and/or modify
;;; it under the terms of the GNU General Public License as published by
;;; the Free Software Foundation; either version 2, or (at your option)
;;; any later version.
;;;
;;; This program is distributed in the hope that it will be useful,
;;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;;; GNU General Public License for more details.
;;;
;;; You should have received a copy of the GNU General Public License
;;; along with this program; if not, write to the Free Software Foundation,
;;; Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.  */

(ns ejc-sql.output
  (:use clojure.java.io
        ejc-sql.lib)
  (:require [clojure.string :as s])
  (:import (java.io File)
           (java.lang.reflect Method)
           (java.util.Date)
           (java.text.SimpleDateFormat)
           (org.apache.commons.lang3 StringUtils)
           (org.hibernate.engine.jdbc.internal BasicFormatterImpl
                                               DDLFormatterImpl)))

(def ^:dynamic *add-outside-borders* true)

(defn get-log-dir []
  (file (if windows?
          (System/getenv "AppData")
          "/var/log/")
        "ejc-sql"))

(defn get-log-file [& [create-new]]
  (loop [xs (take 100 (range))]
    (when xs
      (let [prev-day (first xs)
            log-file (file
                      (get-log-dir)
                      (format "%s.log"
                              (.format
                               (new java.text.SimpleDateFormat
                                    "yyyy-MM-dd")
                               (let [cal (java.util.Calendar/getInstance)]
                                 (.setTime cal (new java.util.Date))
                                 (.add cal java.util.Calendar/DATE
                                       (if prev-day (- prev-day) 0))
                                 (.getTime cal)))))]
        (if (or create-new (.exists log-file))
          log-file
          (recur (next xs)))))))

(defn print-log-file-path []
  (print (.getAbsolutePath (or (get-log-file) (get-log-dir)))))

(defn log-sql [sql]
  (let [log-file (get-log-file true)
        is-new-file (not (.exists log-file))]
    (when is-new-file
      (.mkdirs (File. (.getParent log-file)))
      (.createNewFile log-file))
    (with-open [wrtr (clojure.java.io/writer log-file :append true)]
      (when is-new-file
        (.write wrtr (str "-- -*- mode: sql; -*-\n"
                          "-- Local Variables:\n"
                          "-- eval: (ejc-sql-mode)\n"
                          "-- End:\n")))
      (.write wrtr (str (simple-join 50 "-") " "
                        (.format (new java.text.SimpleDateFormat
                                      "yyyy.MM.dd HH:mm:ss.S")
                                 (new java.util.Date))
                        " " (simple-join 2 "-") "\n" sql "\n")))))

(def fetch-size
  "Limit number of records to output."
  (atom 50))

(def max-rows
  "Limit number of records to contain in ResultSet."
  (atom 99))

(def show-too-many-rows-message
  "Output message in case of ResultSet is bigger than `fetch-size`."
  (atom true))

(def column-width-limit
  "Limit number of chars per column to output."
  (atom 30))

(defn set-fetch-size
  "Set limit for number of records to output. When nil no limit.
  Passed to the JDBC driver a hint as to the number of rows that should be
  fetched from the database when more rows are needed for ResultSet objects
  generated by a Statement."
  [val]
  (reset! fetch-size (or val 0)))

(defn set-max-rows
  "Set limit for number of records to contain in ResultSet. When nil no limit.
  Sets the limit for the maximum number of rows that any ResultSet object
  generated by a Statement object can contain to the given number."
  [val]
  (reset! max-rows (or val 0)))

(defn set-show-too-many-rows-message
  [val]
  (reset! show-too-many-rows-message (or val false)))

(defn set-column-width-limit
  "Set limit for number of chars per column to output. When nil no limit."
  [val]
  (reset! column-width-limit val))

(defn rotate-table
  "Rotate result set to show fields list vertically.
  Applied to single-record result set.
  E.g. transtofm from: a | b | c into: a | 1
                       --+---+--       b | 2
                       1 | 2 | 3       c | 3"
  [data]
  (apply mapv vector data))

(def use-unicode (atom false))

(defn set-use-unicode
  "Set using unicode for grid borders."
  [val]
  (reset! use-unicode val))

(defn u? [unicode-str ascii-str]
  (if @use-unicode unicode-str ascii-str))

(defn print-maps
  "Prints a collection of maps in a textual table. Prints table headings
   ks, and then a line of output for each row, corresponding to the keys
   in ks. If ks are not specified, use the keys of the first item in rows."
  ([ks rows add-borders]
   (when (seq rows)
     (let [widths (map
                   (fn [k]
                     (apply max (count (str k))
                            (map #(count (str (get % k))) rows)))
                   ks)
           spacers (map #(apply str (repeat % (u? "─" "-"))) widths)
           fmts (map #(str "%-" % "s") widths)
           fmt-row (fn [leader divider trailer row]
                     (str (if add-borders leader "")
                          (apply str
                                 (interpose
                                  divider
                                  (for [[col fmt]
                                        (map vector (map #(get row %) ks) fmts)]
                                    (format fmt (str col)))))
                          (if add-borders trailer "")))
           aob *add-outside-borders*]
       (println (fmt-row (if aob (u? "│ " "| ") "") (u? " │ " " | ") (if aob (u? " │" " |") "")
                         (zipmap ks (map name ks))))
       (println (fmt-row (if aob (u? "├─" "|-") "") (u? "─┼─" "-+-") (if aob (u? "─┤" "-|") "")
                         (zipmap ks spacers)))
       (doseq [row rows]
         (println (fmt-row (if aob (u? "│ " "| ") "") (u? " │ " " | ") (if aob (u? " │" " |") "") row))))))
  ([rows add-borders]
   (print-maps (keys (first rows)) rows add-borders)))

(defn trim-max-width [x]
  (let [s (str x)]
    (if (and *max-column-width*
             (> *max-column-width* 0)
             (> (count s) *max-column-width*))
      (str (subs s 0 (- *max-column-width* 3)) "...")
      s)))

(defn min-not-zero [x y]
  (min (if (> x 0) x y)
       (if (> y 0) y x)))

(defn print-table
  "Converts a seq of seqs to a textual table. Uses the first seq as the table
  headings and the remaining seqs as rows."
  ([rows limit]
   (when (seq rows)
     (let [row-limit (or limit (min-not-zero @max-rows @fetch-size))
           [rows msg] (if (and row-limit
                               (> row-limit 0)
                               ;; Since `rows` parameter contains a header
                               ;; `row` 1 should be added to `row-limit`.
                               (> (count rows) (+ row-limit 1)))
                        [(take (+ row-limit 1) rows)
                         (if @show-too-many-rows-message
                           (format "Too many rows. Only %s from %s%s are shown."
                                   row-limit
                                   (min-not-zero @max-rows (- (count rows) 1))
                                   (if (and @max-rows
                                            (> @max-rows 0)
                                            (> (- (count rows) 1) @max-rows))
                                     "+" "")))]
                        [rows ""])
           [headers rows] [(map name (first rows)) (rest rows)]
           aob *add-outside-borders*
           [rows rotated] (if (and (not aob)
                                   (= (count rows) 1)
                                   (> (count (first rows)) 1))
                            ;; Rotatate result set table representation
                            ;; if it has 1 row and many columns.
                            [(rotate-table [headers (first rows)]) true]
                            [rows false])
           [single-column-and-row
            cell-value] (if-let [single-cell? (and (= (count rows) 1)
                                                   (= (count (first rows)) 1))]
                          [single-cell? (ffirst rows)])
           [headers rows] (if (or rotated single-column-and-row)
                            ;; Do not restrict column width if result
                            ;; set has only 1 row or 1 column & 1 row.
                            [headers rows]
                            [(map trim-max-width headers)
                             (map #(map trim-max-width %) rows)])
           rn #"(\r\n)|\n|\r"
           nl (System/getProperty "line.separator")
           rows (for [row rows]
                  (map #(if (string? %)
                          (if single-column-and-row
                            ;; In case of only 1 column & 1 row unify
                            ;; newline separators to system line break.
                            (s/replace % rn nl)
                            (s/replace % rn " "))
                          ;; Do not remove newline separators if result
                          ;; set cell is not a string.
                          %)
                       row))
           widths (if single-column-and-row
                    (list
                     (apply max
                            (cons
                             (count (first headers))
                             (if (string? cell-value)
                               (map count (s/split cell-value rn))
                               (list (count (str cell-value)))))))
                    (for [col (rotate-table (conj rows headers))]
                      (apply max (map #(count (str %)) col))))
           spacers (map #(apply str (repeat % (u? "─" "-"))) widths)
           ;; TODO: #(str "%" % "d") for numbers
           fmts (if (and rotated (not aob))
                  ;; Remove trailing spaces for data column of single-row
                  ;; result if no outer borders required.
                  (list (str "%-" (first widths) "s") "%s")
                  (map #(str "%-" % "s") widths))
           fmt-row (fn [leader divider trailer row]
                     (str leader
                          (apply str (interpose
                                      divider
                                      (for [[col fmt] (map vector row fmts)]
                                        (format fmt (str col)))))
                          trailer))]
       (when (not-empty msg)
         (println msg)
         (println))
       (if (not rotated)
         (do
           (println (fmt-row (if aob (u? "│ " "| ") "") (u? " │ " " | ") (if aob (u? " │" " |") "") headers))
           (println (fmt-row (if aob (u? "├─" "|-") "") (u? "─┼─" "-+-") (if aob (u? "─┤" "-|") "") spacers))))
       (doseq [row (if (and aob single-column-and-row (string? cell-value))
                     (map list (s/split cell-value (re-pattern nl)))
                     rows)]
         (println (fmt-row (if aob (u? "│ " "| ") "") (u? " │ " " | ") (if aob (u? " │" " |") "") row))))))
  ([rows] (print-table rows @fetch-size)))

(defn unify-str
  "Concatenate stings, unify newline separators to the system line break."
  [& s]
  (if (empty? (filter not-empty s))
    ""
    (apply str (map #(s/replace %
                                #"(\r\n)|\n|\r"
                                (System/getProperty "line.separator"))
                    s))))

(defn format-sql [sql]
  (s/trim
   (let [sql (s/trim sql)]
     (.format (if (dml? sql)
                (BasicFormatterImpl.)
                (DDLFormatterImpl.))
              sql))))

(defn format-sql-print
  "SQL should be printed to provide cross-platform newline handling."
  [sql]
  (print (format-sql sql)))

(defn format-sql-if-required [sql]
  (if (> (StringUtils/countMatches sql "\n") 1)
    sql
    (format-sql sql)))

(defn write-result-file [text & {:keys [result-file
                                        append]
                                 :or {append false}}]
  (spit result-file text :append append)
  result-file)

(defn clear-result-file [& {:keys [result-file]}]
  (write-result-file "" :result-file result-file))
