;;; ejc-autocomplete.el -- SQL completitions at point (the part of ejc-sql).

;;; Copyright © 2013-2014 - Kostafey <kostafey@gmail.com>

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

(require 'auto-complete)
(require 'ejc-lib)

(defvar ejc-owners-cache nil
  "Owners list cache.
The owners list probably should not be changed very often.")

(defvar ejc-tables-cache nil
  "Tables list cache.")

(defun ejc-invalidate-cache ()
  (interactive)
  (setq ejc-owners-cache nil)
  (setq ejc-tables-cache nil))

;;;###autoload
(defun ejc--select-db-meta-script (meta-type &optional owner table)
  (let ((owner (if owner
                   (ejc-add-squotes owner)
                 (ejc-add-squotes ejc-db-owner))))
    (cond
     ;;----------
     ;; informix
     ;;----------
     ((string-match "informix" ejc-db-type)
      (cond
       ((eq :owners meta-type) nil)
       ((eq :tables meta-type)
        (concat " SELECT TRIM(t.tabname) as tablesList "
                " FROM systables AS t   "
                " WHERE t.tabtype = 'T' "
                "   AND t.tabid >= 100  "
                " ORDER BY t.tabname;   ")))
      ((eq :columns meta-type)
       (concat " SELECT TRIM(c.colname) AS column_name \n"
               "  FROM systables AS t, syscolumns AS c \n"
               " WHERE t.tabid = c.tabid               \n"
               "   AND t.tabtype = 'T'                 \n"
               "   AND t.tabid >= 100                  \n"
               "   AND TRIM(t.tabname) = '" table "'   \n"
               " ORDER BY c.colno;                     \n")))
     ;;-------
     ;; mysql
     ;;-------
     ((string-match "mysql" ejc-db-type)
      (cond
       ((eq :owners meta-type) nil)
       ((eq :tables meta-type)
        (concat
         " SELECT table_name FROM INFORMATION_SCHEMA.TABLES "
         " WHERE table_schema = '" ejc-db-name "'"))))
     ;;--------
     ;; oracle
     ;;--------
     ((string-match "oracle" ejc-db-type)
      (let ((owner (upcase owner)))
        (cond
         ((eq :owners meta-type)
          (concat "select DISTINCT(owner) "
                  " from ALL_OBJECTS"))
         ((eq :tables meta-type)
          (concat " SELECT table_name, owner \n"
                  " FROM all_tables          \n"
                  (if owner
                      (concat " WHERE owner = " owner) "")))
         ((eq :columns meta-type)
          (concat " SELECT column_name             \n"
                  " FROM ALL_TAB_COLUMNS           \n"
                  " WHERE table_name = '" table "' \n"))
         ((eq :constraints meta-type)
          (if table
              (concat " SELECT * FROM all_constraints    \n"
                      " WHERE owner = "owner"            \n"
                      "       AND table_name = '"table"' \n")
            "SELECT * FROM user_constraints"))
         ((eq :procedures meta-type)
          (concat " SELECT object_name, procedure_name \n"
                  " FROM all_procedures                \n"
                  " WHERE owner = "owner"              \n"))
         ((eq :objects meta-type)
          (concat "SELECT * FROM all_objects WHERE object_type IN "
                  "('FUNCTION','PROCEDURE','PACKAGE')")))))
     ;;--------
     ;; h2
     ;;--------
     ((string-match "h2" ejc-db-type)
      ((eq :tables meta-type)
       (concat "SELECT TABLE_NAME              \n"
               "FROM INFORMATION_SCHEMA.TABLES \n"
               "WHERE TABLE_SCHEMA='PUBLIC'"))))))

(defun ejc-get-owners-list ()
  (ejc--eval-get-list (ejc--select-db-meta-script :owners)))

(defun ejc-get-tables-list (&optional owner)
  (ejc--eval-get-list (ejc--select-db-meta-script :tables owner)))

(defun ejc-get-columns-list (owner table)
  (ejc--eval-get-list (ejc--select-db-meta-script :columns owner table)))

(defun ejc-get-procedures-list (&optional owner)
  (ejc--eval-get-list (ejc--select-db-meta-script :procedures)))

(defun ejc-get-prefix-word ()
  "Return the word preceding dot before the typing."
  (save-excursion
    (let ((dot (save-excursion (search-backward "." nil t)))
          (space (re-search-backward "[ \n\t\r.]+" nil t)))
      (if (and dot
               space
               (> dot space)) ; is a dot completition
          (buffer-substring (1+ space) dot)
        nil))))

(defun ejc-get-completitions-list ()
  "Possible completions list according to already typed prefixes."
  (message "Reciving database srtucture...")
  (let ((need-owners? (ejc--select-db-meta-script :owners)))
    (if (and need-owners? (not ejc-owners-cache))
        (setq ejc-owners-cache (ejc-get-owners-list)))
    (let* ((prefix-1 (ejc-get-prefix-word))
           (prefix-2 (save-excursion
                       (search-backward "." nil t)
                       (ejc-get-prefix-word)))
           (owner
            (cond ((and prefix-1
                        (not prefix-2)
                        (member prefix-1 ejc-owners-cache)) prefix-1)
                  ((and prefix-2
                        (member prefix-2 ejc-owners-cache)) prefix-2)
                  (t ejc-db-owner)))
           (tables-list (let ((cache (lax-plist-get ejc-tables-cache owner)))
                          (if cache
                              cache
                            (let ((new-cach (ejc-get-tables-list owner)))
                              (setq ejc-tables-cache
                                    (lax-plist-put
                                     ejc-tables-cache owner new-cach))
                              new-cach))))
           (table (if (and prefix-1
                           (not (equal prefix-1 owner))
                           (member prefix-1 tables-list))
                      prefix-1)))
      (message "")
      (cond
       ;; owner.table._<colomns-list>
       (prefix-2 (ejc-get-columns-list owner table))
       ;; [owner|table]._<tables-list|colomns-list>
       (prefix-1 (if (and need-owners?
                          (member prefix-1 ejc-owners-cache))
                     tables-list
                   (if (member prefix-1 tables-list)
                       (ejc-get-columns-list owner table))))
       ;; _<owners-list&tables-list>
       (t (append ejc-owners-cache tables-list))))))

;;;###autoload
(defun ejc-candidates ()
  (append '("select" "where" "from" "insert" "update" "delete" "drop")
           (ejc-get-completitions-list)))

(defvar ac-source-ejc-sql
  '((candidates . ejc-candidates)))

;;;###autoload
(defun ejc-ac-setup ()
  "Add the completion sources to the front of `ac-sources'.
This affects only the current buffer."
  (interactive)
  (add-to-list 'ac-sources 'ac-source-ejc-sql)
  ;(setq ac-auto-start 0)
  )

(provide 'ejc-autocomplete)
