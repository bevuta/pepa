BEGIN;

DROP TABLE IF EXISTS document_pages;
DROP TABLE IF EXISTS document_tags;
DROP TABLE IF EXISTS tags;
DROP TABLE IF EXISTS documents;
DROP TABLE IF EXISTS pages CASCADE;
DROP TABLE IF EXISTS page_images CASCADE;
DROP TABLE IF EXISTS files CASCADE;
DROP TABLE IF EXISTS inbox;

DROP TYPE IF EXISTS PROCESSING_STATUS;

CREATE TYPE PROCESSING_STATUS AS ENUM ('pending', 'failed', 'processed');

DROP FUNCTION IF EXISTS utc_now();

CREATE FUNCTION utc_now() RETURNS TIMESTAMP AS $$
       select (NOW() AT TIME ZONE 'utc');
$$ LANGUAGE SQL;

CREATE TABLE files (
       id SERIAL PRIMARY KEY CHECK(id > 0),
       data BYTEA NOT NULL,
       content_type TEXT NOT NULL,
       status PROCESSING_STATUS NOT NULL DEFAULT 'pending',
       origin TEXT NOT NULL,
       name TEXT,
       report TEXT CHECK ((report IS NULL) = (status = 'pending'))
);

ALTER TABLE files ALTER COLUMN data SET STORAGE EXTERNAL;

CREATE TABLE documents (
       id SERIAL PRIMARY KEY CHECK(id > 0),
       title TEXT NOT NULL,
       modified TIMESTAMP,
       created TIMESTAMP WITHOUT TIME ZONE DEFAULT (NOW() AT TIME ZONE 'utc'),
       notes TEXT,
       file INT REFERENCES files --if set, this document correspondents exactly to file
       -- TODO: Add check to make sure all pages in document_pages from from document.file if NOT NULL
);

CREATE TABLE pages (
       id SERIAL PRIMARY KEY CHECK(id > 0),
       file INT NOT NULL REFERENCES files,
       number INT NOT NULL CHECK (number >= 0),
       text TEXT,
       ocr_text TEXT,
       ocr_status PROCESSING_STATUS NOT NULL DEFAULT 'pending',
       rotation INT NOT NULL DEFAULT 0
);

CREATE TABLE page_images (
       page INT NOT NULL REFERENCES pages,
       dpi INT NOT NULL CHECK (dpi > 0),
       image BYTEA NOT NULL,
       hash TEXT NOT NULL
);

ALTER TABLE page_images ALTER COLUMN image SET STORAGE EXTERNAL;


CREATE TABLE document_pages (
       document INT NOT NULL REFERENCES documents,
       page INT NOT NULL REFERENCES pages,
       number INT NOT NULL CHECK (number >= 0),
       UNIQUE (document, number)
);

CREATE TABLE tags (
       id SERIAL PRIMARY KEY CHECK (id > 0),
       name TEXT NOT NULL,
       UNIQUE (name)
);

CREATE TABLE document_tags (
       document INT NOT NULL REFERENCES documents,
       tag INT NOT NULL REFERENCES tags,
       UNIQUE (document, tag),
       seq SERIAL CHECK (seq > 0)
);

CREATE TABLE inbox (
       page INT NOT NULL REFERENCES pages
);

-- Triggers used to update the 'modified' property of documents

DROP TRIGGER IF EXISTS touch_document on documents;
DROP FUNCTION IF EXISTS touch_document_func();

CREATE FUNCTION touch_document_func() RETURNS TRIGGER AS $$
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

DROP TRIGGER IF EXISTS touch_document_tags_insert on document_tags;
DROP TRIGGER IF EXISTS touch_document_tags_delete on document_tags;
DROP FUNCTION IF EXISTS touch_document_tags_insert_func();
DROP FUNCTION IF EXISTS touch_document_tags_delete_func();

CREATE FUNCTION touch_document_tags_insert_func() RETURNS TRIGGER AS $$
       BEGIN
         UPDATE documents SET modified = UTC_NOW() WHERE id = NEW.document;
         RETURN NEW;
       END;
$$ LANGUAGE PLPGSQL;

CREATE FUNCTION touch_document_tags_delete_func() RETURNS TRIGGER AS $$
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

DROP INDEX IF EXISTS pages_fulltext_idx;
CREATE INDEX pages_fulltext_idx ON pages
    USING gin(to_tsvector('simple', coalesce(text, '') || ' ' || coalesce(ocr_text, '')));

DROP FUNCTION IF EXISTS pages_fulltext(text);
CREATE OR REPLACE FUNCTION pages_fulltext(val text) RETURNS setof pages AS $$
  BEGIN
    RETURN QUERY (SELECT *
                  FROM pages
                  WHERE (to_tsvector('simple', coalesce(text, '') || ' ' || coalesce(ocr_text, '')) @@ to_tsquery('simple', val)));
  END;
$$  LANGUAGE PLPGSQL;

COMMIT;
