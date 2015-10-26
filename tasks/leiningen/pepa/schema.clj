(ns leiningen.pepa.schema
  (:require [clojure.string :as s]))

(def prologue
  "
DROP TYPE IF EXISTS PROCESSING_STATUS;
DROP TYPE IF EXISTS ENTITY;

CREATE TYPE PROCESSING_STATUS AS ENUM ('pending', 'failed', 'processed');
CREATE TYPE ENTITY AS ENUM ('files', 'documents', 'pages', 'inbox', 'tags');
")

(def tables
  [["files"
    {:columns "
       id SERIAL PRIMARY KEY CHECK(id > 0),
       data BYTEA NOT NULL,
       content_type TEXT NOT NULL,
       status PROCESSING_STATUS NOT NULL DEFAULT 'pending',
       origin TEXT NOT NULL,
       name TEXT,
       report TEXT CHECK ((report IS NULL) = (status = 'pending'))"
     :after "ALTER TABLE files ALTER COLUMN data SET STORAGE EXTERNAL;"
     :state-seq? true
     :track-deletions? true}]

   ["documents"
    {:columns "
       id SERIAL PRIMARY KEY CHECK(id > 0),
       title TEXT NOT NULL,
       modified TIMESTAMP,
       document_date DATE,
       created TIMESTAMP WITHOUT TIME ZONE DEFAULT (NOW() AT TIME ZONE 'utc'),
       notes TEXT,
       -- If set, this document correspondents exactly to file. This
          is used by the page_renderer to set the pages for this
          document. It will clear that flag when rendering is
          complete.
       -- TODO: Add check to make sure all pages in document_pages are from document.file if NOT NULL
       file INT REFERENCES files"
     :state-seq? true
     :track-deletions? true}]

   ["pages"
    {:columns "
       id SERIAL PRIMARY KEY CHECK(id > 0),
       file INT NOT NULL REFERENCES files,
       number INT NOT NULL CHECK (number >= 0),
       text TEXT,
       ocr_text TEXT,
       ocr_status PROCESSING_STATUS NOT NULL DEFAULT 'pending',
       rotation INT NOT NULL DEFAULT 0,
       render_status PROCESSING_STATUS NOT NULL DEFAULT 'pending'"
     :state-seq? true
     :track-deletions? true}]

   ["page_images"
    {:columns "
       page INT NOT NULL REFERENCES pages,
       dpi INT NOT NULL CHECK (dpi > 0),
       image BYTEA NOT NULL,
       hash TEXT NOT NULL"
     :after "ALTER TABLE page_images ALTER COLUMN image SET STORAGE EXTERNAL;"
     :state-seq? true}]

   ["document_pages"
    {:columns "
       document INT NOT NULL REFERENCES documents,
       page INT NOT NULL REFERENCES pages,
       number INT NOT NULL CHECK (number >= 0),
       UNIQUE (document, number)"
     :state-seq? true}]

   ["tags"
    {:columns "
       id SERIAL PRIMARY KEY CHECK (id > 0),
       name TEXT NOT NULL,
       UNIQUE (name)"}]

   ["document_tags"
    {:columns "
       document INT NOT NULL REFERENCES documents,
       tag INT NOT NULL REFERENCES tags,
       UNIQUE (document, tag),
       seq SERIAL CHECK (seq > 0)"
     :state-seq? true
     :track-deletions? "tags"
     :entity-id-field "tag"}]

   ["inbox"
    {:columns "
       page INT NOT NULL REFERENCES pages"
     :state-seq? true
     :track-deletions? true
     :entity-id-field "page"}]

   ["deletions"
    {:columns "
       id INT NOT NULL,
       entity ENTITY NOT NULL"
     :state-seq? true}]])

(def epilogue
  "
CREATE OR REPLACE FUNCTION utc_now() RETURNS TIMESTAMP AS $$
       SELECT (NOW() AT TIME ZONE 'UTC');
$$ LANGUAGE SQL;


-- Triggers used to update the 'modified' property of documents

CREATE OR REPLACE FUNCTION touch_document_func() RETURNS TRIGGER AS $$
       BEGIN
         NEW.modified := UTC_NOW();
         RETURN NEW;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER touch_document
  BEFORE UPDATE
  ON documents
  FOR EACH ROW
  EXECUTE PROCEDURE touch_document_func();

CREATE OR REPLACE FUNCTION touch_document_tags_insert_func() RETURNS TRIGGER AS $$
       BEGIN
         UPDATE documents SET modified = UTC_NOW() WHERE id = NEW.document;
         RETURN NEW;
       END;
$$ LANGUAGE PLPGSQL;

CREATE OR REPLACE FUNCTION touch_document_tags_delete_func() RETURNS TRIGGER AS $$
       BEGIN
         UPDATE documents SET modified = UTC_NOW() WHERE id = OLD.document;
         RETURN OLD;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER touch_document_tags_insert
  AFTER insert
  ON document_tags
  FOR EACH ROW
  EXECUTE PROCEDURE touch_document_tags_insert_func();

CREATE TRIGGER touch_document_tags_delete
  AFTER DELETE
  ON document_tags
  FOR EACH ROW
  EXECUTE PROCEDURE touch_document_tags_delete_func();


-- Full Text Search

CREATE INDEX pages_fulltext_idx ON pages
    USING gin(to_tsvector('simple', coalesce(text, '') || ' ' || coalesce(ocr_text, '')));

CREATE OR REPLACE FUNCTION pages_fulltext(val text) RETURNS setof pages AS $$
  BEGIN
    RETURN QUERY (SELECT *
                  FROM pages
                  WHERE (to_tsvector('simple', coalesce(text, '') || ' ' || coalesce(ocr_text, '')) @@ to_tsquery('simple', val)));
  END;
$$  LANGUAGE PLPGSQL;
")

(defn drop-table [name & [{:keys [state-seq?]}]]
  (printf "DROP TABLE IF EXISTS %s CASCADE;" name)
  (when state-seq?
    (drop-table (str name "_state_seq")))
  (newline))

(defn create-table [name spec]
  (printf "CREATE TABLE %s (%s);" name spec)
  (newline))

(defn add-state-seq [table]
  (printf "
ALTER TABLE %1$s ADD COLUMN state_seq BIGINT NOT NULL CHECK(state_seq > 0);

CREATE TABLE %1$s_state_seq (
       current BIGINT NOT NULL CHECK (current >= 0)
);

INSERT INTO %1$s_state_seq (current) VALUES (0);

CREATE OR REPLACE FUNCTION update_%1$s_state_seq_func() RETURNS TRIGGER AS $$
       BEGIN
         UPDATE %1$s_state_seq SET current = current + 1 RETURNING current INTO NEW.state_seq;
         RETURN NEW;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER update_%1$s_state_seq_trigger
  BEFORE UPDATE
  ON %1$s
  FOR EACH ROW
  WHEN (NEW.* IS DISTINCT FROM OLD.*)
  EXECUTE PROCEDURE update_%1$s_state_seq_func();

CREATE TRIGGER insert_%1$s_state_seq_trigger
  BEFORE INSERT
  ON %1$s
  FOR EACH ROW
  EXECUTE PROCEDURE update_%1$s_state_seq_func();
" table))

(defn add-deletion-trigger [table entity-name id-field]
  (printf "

CREATE OR REPLACE FUNCTION delete_%1$s_track_func() RETURNS TRIGGER AS $$
       BEGIN
         INSERT INTO deletions (entity, id) VALUES ('%2$s', OLD.%3$s);
         RETURN OLD;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER delete_%1$s_track_trigger
  AFTER DELETE ON %1$s
  FOR EACH ROW
  EXECUTE PROCEDURE delete_%1$s_track_func();
" table (name entity-name) id-field))


(defn maybe-println [x]
  (when x
    (newline)
    (println x)))

(defn run [args]
  (println "-- NOTE: DON'T EDIT")
  (println "-- This file is generated via `lein pepa schema` which is defined in tasks/leiningen/pepa/schema.clj")
  (newline)
  (println "BEGIN;")
  (doseq [[table spec] tables]
    (drop-table table spec))
  (newline)
  (println prologue)
  (doseq [[table spec] tables]
    (create-table table (:columns spec))
    (when (:state-seq? spec)
      (add-state-seq table)) 
    (maybe-println (:after spec))
    (when-let [track-deletions (:track-deletions? spec)]
      (let [entity (if (string? track-deletions)
                     track-deletions
                     table)]
       (add-deletion-trigger table entity (or (:entity-id-field spec) "id"))))
    (newline))
  (println epilogue)
  (println "COMMIT;")
  (flush))
