-- NOTE: DON'T EDIT
-- This file is generated via `lein pepa schema` which is defined in tasks/leiningen/pepa/schema.clj

BEGIN;
DROP TABLE IF EXISTS files CASCADE;DROP TABLE IF EXISTS files_state_seq CASCADE;

DROP TABLE IF EXISTS documents CASCADE;DROP TABLE IF EXISTS documents_state_seq CASCADE;

DROP TABLE IF EXISTS pages CASCADE;DROP TABLE IF EXISTS pages_state_seq CASCADE;

DROP TABLE IF EXISTS page_images CASCADE;DROP TABLE IF EXISTS page_images_state_seq CASCADE;

DROP TABLE IF EXISTS document_pages CASCADE;DROP TABLE IF EXISTS document_pages_state_seq CASCADE;

DROP TABLE IF EXISTS tags CASCADE;
DROP TABLE IF EXISTS document_tags CASCADE;DROP TABLE IF EXISTS document_tags_state_seq CASCADE;

DROP TABLE IF EXISTS inbox CASCADE;DROP TABLE IF EXISTS inbox_state_seq CASCADE;

DROP TABLE IF EXISTS deletions CASCADE;DROP TABLE IF EXISTS deletions_state_seq CASCADE;



DROP TYPE IF EXISTS PROCESSING_STATUS;
DROP TYPE IF EXISTS ENTITY;

CREATE TYPE PROCESSING_STATUS AS ENUM ('pending', 'failed', 'processed');
CREATE TYPE ENTITY AS ENUM ('files', 'documents', 'pages', 'inbox', 'tags');

CREATE TABLE files (
       id SERIAL PRIMARY KEY CHECK(id > 0),
       data BYTEA NOT NULL,
       content_type TEXT NOT NULL,
       status PROCESSING_STATUS NOT NULL DEFAULT 'pending',
       origin TEXT NOT NULL,
       name TEXT,
       report TEXT CHECK ((report IS NULL) = (status = 'pending')));

ALTER TABLE files ADD COLUMN state_seq BIGINT NOT NULL CHECK(state_seq > 0);

CREATE TABLE files_state_seq (
       current BIGINT NOT NULL CHECK (current >= 0)
);

INSERT INTO files_state_seq (current) VALUES (0);

CREATE OR REPLACE FUNCTION update_files_state_seq_func() RETURNS TRIGGER AS $$
       BEGIN
         UPDATE files_state_seq SET current = current + 1 RETURNING current INTO NEW.state_seq;
         RETURN NEW;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER update_files_state_seq_trigger
  BEFORE UPDATE
  ON files
  FOR EACH ROW
  WHEN (NEW.* IS DISTINCT FROM OLD.*)
  EXECUTE PROCEDURE update_files_state_seq_func();

CREATE TRIGGER insert_files_state_seq_trigger
  BEFORE INSERT
  ON files
  FOR EACH ROW
  EXECUTE PROCEDURE update_files_state_seq_func();


CREATE OR REPLACE FUNCTION delete_files_track_func() RETURNS TRIGGER AS $$
       BEGIN
         INSERT INTO deletions (entity, id) VALUES ('files', OLD.id);
         RETURN OLD;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER delete_files_track_trigger
  AFTER DELETE ON files
  FOR EACH ROW
  EXECUTE PROCEDURE delete_files_track_func();

CREATE TABLE documents (
       id SERIAL PRIMARY KEY CHECK(id > 0),
       title TEXT NOT NULL,
       modified TIMESTAMP,
       document_date TIMESTAMP,
       created TIMESTAMP WITHOUT TIME ZONE DEFAULT (NOW() AT TIME ZONE 'utc'),
       notes TEXT,
       -- If set, this document correspondents exactly to file
       -- TODO: Add check to make sure all pages in document_pages are from document.file if NOT NULL
       file INT REFERENCES files);

ALTER TABLE documents ADD COLUMN state_seq BIGINT NOT NULL CHECK(state_seq > 0);

CREATE TABLE documents_state_seq (
       current BIGINT NOT NULL CHECK (current >= 0)
);

INSERT INTO documents_state_seq (current) VALUES (0);

CREATE OR REPLACE FUNCTION update_documents_state_seq_func() RETURNS TRIGGER AS $$
       BEGIN
         UPDATE documents_state_seq SET current = current + 1 RETURNING current INTO NEW.state_seq;
         RETURN NEW;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER update_documents_state_seq_trigger
  BEFORE UPDATE
  ON documents
  FOR EACH ROW
  WHEN (NEW.* IS DISTINCT FROM OLD.*)
  EXECUTE PROCEDURE update_documents_state_seq_func();

CREATE TRIGGER insert_documents_state_seq_trigger
  BEFORE INSERT
  ON documents
  FOR EACH ROW
  EXECUTE PROCEDURE update_documents_state_seq_func();


CREATE OR REPLACE FUNCTION delete_documents_track_func() RETURNS TRIGGER AS $$
       BEGIN
         INSERT INTO deletions (entity, id) VALUES ('documents', OLD.id);
         RETURN OLD;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER delete_documents_track_trigger
  AFTER DELETE ON documents
  FOR EACH ROW
  EXECUTE PROCEDURE delete_documents_track_func();

CREATE TABLE pages (
       id SERIAL PRIMARY KEY CHECK(id > 0),
       file INT NOT NULL REFERENCES files,
       number INT NOT NULL CHECK (number >= 0),
       text TEXT,
       ocr_text TEXT,
       ocr_status PROCESSING_STATUS NOT NULL DEFAULT 'pending',
       rotation INT NOT NULL DEFAULT 0,
       render_status PROCESSING_STATUS NOT NULL DEFAULT 'pending');

ALTER TABLE pages ADD COLUMN state_seq BIGINT NOT NULL CHECK(state_seq > 0);

CREATE TABLE pages_state_seq (
       current BIGINT NOT NULL CHECK (current >= 0)
);

INSERT INTO pages_state_seq (current) VALUES (0);

CREATE OR REPLACE FUNCTION update_pages_state_seq_func() RETURNS TRIGGER AS $$
       BEGIN
         UPDATE pages_state_seq SET current = current + 1 RETURNING current INTO NEW.state_seq;
         RETURN NEW;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER update_pages_state_seq_trigger
  BEFORE UPDATE
  ON pages
  FOR EACH ROW
  WHEN (NEW.* IS DISTINCT FROM OLD.*)
  EXECUTE PROCEDURE update_pages_state_seq_func();

CREATE TRIGGER insert_pages_state_seq_trigger
  BEFORE INSERT
  ON pages
  FOR EACH ROW
  EXECUTE PROCEDURE update_pages_state_seq_func();


CREATE OR REPLACE FUNCTION delete_pages_track_func() RETURNS TRIGGER AS $$
       BEGIN
         INSERT INTO deletions (entity, id) VALUES ('pages', OLD.id);
         RETURN OLD;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER delete_pages_track_trigger
  AFTER DELETE ON pages
  FOR EACH ROW
  EXECUTE PROCEDURE delete_pages_track_func();

CREATE TABLE page_images (
       page INT NOT NULL REFERENCES pages,
       dpi INT NOT NULL CHECK (dpi > 0),
       image BYTEA NOT NULL,
       hash TEXT NOT NULL);

ALTER TABLE page_images ADD COLUMN state_seq BIGINT NOT NULL CHECK(state_seq > 0);

CREATE TABLE page_images_state_seq (
       current BIGINT NOT NULL CHECK (current >= 0)
);

INSERT INTO page_images_state_seq (current) VALUES (0);

CREATE OR REPLACE FUNCTION update_page_images_state_seq_func() RETURNS TRIGGER AS $$
       BEGIN
         UPDATE page_images_state_seq SET current = current + 1 RETURNING current INTO NEW.state_seq;
         RETURN NEW;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER update_page_images_state_seq_trigger
  BEFORE UPDATE
  ON page_images
  FOR EACH ROW
  WHEN (NEW.* IS DISTINCT FROM OLD.*)
  EXECUTE PROCEDURE update_page_images_state_seq_func();

CREATE TRIGGER insert_page_images_state_seq_trigger
  BEFORE INSERT
  ON page_images
  FOR EACH ROW
  EXECUTE PROCEDURE update_page_images_state_seq_func();

ALTER TABLE page_images ALTER COLUMN image SET STORAGE EXTERNAL;

CREATE TABLE document_pages (
       document INT NOT NULL REFERENCES documents,
       page INT NOT NULL REFERENCES pages,
       number INT NOT NULL CHECK (number >= 0),
       UNIQUE (document, number));

ALTER TABLE document_pages ADD COLUMN state_seq BIGINT NOT NULL CHECK(state_seq > 0);

CREATE TABLE document_pages_state_seq (
       current BIGINT NOT NULL CHECK (current >= 0)
);

INSERT INTO document_pages_state_seq (current) VALUES (0);

CREATE OR REPLACE FUNCTION update_document_pages_state_seq_func() RETURNS TRIGGER AS $$
       BEGIN
         UPDATE document_pages_state_seq SET current = current + 1 RETURNING current INTO NEW.state_seq;
         RETURN NEW;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER update_document_pages_state_seq_trigger
  BEFORE UPDATE
  ON document_pages
  FOR EACH ROW
  WHEN (NEW.* IS DISTINCT FROM OLD.*)
  EXECUTE PROCEDURE update_document_pages_state_seq_func();

CREATE TRIGGER insert_document_pages_state_seq_trigger
  BEFORE INSERT
  ON document_pages
  FOR EACH ROW
  EXECUTE PROCEDURE update_document_pages_state_seq_func();

CREATE TABLE tags (
       id SERIAL PRIMARY KEY CHECK (id > 0),
       name TEXT NOT NULL,
       UNIQUE (name));

CREATE TABLE document_tags (
       document INT NOT NULL REFERENCES documents,
       tag INT NOT NULL REFERENCES tags,
       UNIQUE (document, tag),
       seq SERIAL CHECK (seq > 0));

ALTER TABLE document_tags ADD COLUMN state_seq BIGINT NOT NULL CHECK(state_seq > 0);

CREATE TABLE document_tags_state_seq (
       current BIGINT NOT NULL CHECK (current >= 0)
);

INSERT INTO document_tags_state_seq (current) VALUES (0);

CREATE OR REPLACE FUNCTION update_document_tags_state_seq_func() RETURNS TRIGGER AS $$
       BEGIN
         UPDATE document_tags_state_seq SET current = current + 1 RETURNING current INTO NEW.state_seq;
         RETURN NEW;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER update_document_tags_state_seq_trigger
  BEFORE UPDATE
  ON document_tags
  FOR EACH ROW
  WHEN (NEW.* IS DISTINCT FROM OLD.*)
  EXECUTE PROCEDURE update_document_tags_state_seq_func();

CREATE TRIGGER insert_document_tags_state_seq_trigger
  BEFORE INSERT
  ON document_tags
  FOR EACH ROW
  EXECUTE PROCEDURE update_document_tags_state_seq_func();


CREATE OR REPLACE FUNCTION delete_document_tags_track_func() RETURNS TRIGGER AS $$
       BEGIN
         INSERT INTO deletions (entity, id) VALUES ('tags', OLD.tag);
         RETURN OLD;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER delete_document_tags_track_trigger
  AFTER DELETE ON document_tags
  FOR EACH ROW
  EXECUTE PROCEDURE delete_document_tags_track_func();

CREATE TABLE inbox (
       page INT NOT NULL REFERENCES pages);

ALTER TABLE inbox ADD COLUMN state_seq BIGINT NOT NULL CHECK(state_seq > 0);

CREATE TABLE inbox_state_seq (
       current BIGINT NOT NULL CHECK (current >= 0)
);

INSERT INTO inbox_state_seq (current) VALUES (0);

CREATE OR REPLACE FUNCTION update_inbox_state_seq_func() RETURNS TRIGGER AS $$
       BEGIN
         UPDATE inbox_state_seq SET current = current + 1 RETURNING current INTO NEW.state_seq;
         RETURN NEW;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER update_inbox_state_seq_trigger
  BEFORE UPDATE
  ON inbox
  FOR EACH ROW
  WHEN (NEW.* IS DISTINCT FROM OLD.*)
  EXECUTE PROCEDURE update_inbox_state_seq_func();

CREATE TRIGGER insert_inbox_state_seq_trigger
  BEFORE INSERT
  ON inbox
  FOR EACH ROW
  EXECUTE PROCEDURE update_inbox_state_seq_func();


CREATE OR REPLACE FUNCTION delete_inbox_track_func() RETURNS TRIGGER AS $$
       BEGIN
         INSERT INTO deletions (entity, id) VALUES ('inbox', OLD.page);
         RETURN OLD;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER delete_inbox_track_trigger
  AFTER DELETE ON inbox
  FOR EACH ROW
  EXECUTE PROCEDURE delete_inbox_track_func();

CREATE TABLE deletions (
       id INT NOT NULL,
       entity ENTITY NOT NULL);

ALTER TABLE deletions ADD COLUMN state_seq BIGINT NOT NULL CHECK(state_seq > 0);

CREATE TABLE deletions_state_seq (
       current BIGINT NOT NULL CHECK (current >= 0)
);

INSERT INTO deletions_state_seq (current) VALUES (0);

CREATE OR REPLACE FUNCTION update_deletions_state_seq_func() RETURNS TRIGGER AS $$
       BEGIN
         UPDATE deletions_state_seq SET current = current + 1 RETURNING current INTO NEW.state_seq;
         RETURN NEW;
       END;
$$ LANGUAGE PLPGSQL;

CREATE TRIGGER update_deletions_state_seq_trigger
  BEFORE UPDATE
  ON deletions
  FOR EACH ROW
  WHEN (NEW.* IS DISTINCT FROM OLD.*)
  EXECUTE PROCEDURE update_deletions_state_seq_func();

CREATE TRIGGER insert_deletions_state_seq_trigger
  BEFORE INSERT
  ON deletions
  FOR EACH ROW
  EXECUTE PROCEDURE update_deletions_state_seq_func();


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

COMMIT;
