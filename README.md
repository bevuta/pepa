# Pepa DMS

## What is Pepa?

Pepa is a document management system primarily designed for archiving
and retrieving your digital documents. It's implemented in Clojure and
ClojureScript. Pepa can be used from within your browser and - this
goes without saying - you can view all your documents there.

### How does Pepa work?

Any document management system (any archive whatsoever really) would
be pretty useless if it would only store documents. To be more than
just a black hole for documents, a DMS should aid you in organizing
the documents. You should be able to not only store your documents,
but to search for them and - even more importantly - to find them.

Pepa uses a tagging system inspired by
[Notmuch](http://notmuchmail.org/) to achieve this. A tag is a text
label that the user adds to a document. This could be anything from
`invoice` to `university` or `work` or `doctor` or `contracts` or
`papers` or ... Of course you can add more than one tag to a document.
A document tagged with `invoice` can additionally be tagged with
`unpaid` or `paid` for example.

Those tags can be used to find a document. You want to find every
document that's an unpaid invoice? Easy, let Pepa search for documents
which are tagged with `invoice` and `unpaid` - once you have paid
those invoice, you can remove the `unpaid` tag and add the `paid` tag
instead.

Of course, Pepa allows not only searching by tag. Every document in
Pepa is available for full-text search. You can combine this search
with the tag search to increase your chances of finding just the
right document.


### Current features

- Supported file formats
  - PDF
- Import channels
  - SMTP / email (attachments are extracted)
  - HTTP (API and upload via web interface)
  - Virtual Network Printer (LPD)
- Manual tagging
- Full-text search
- OCR (via Tesseract and/or CuneiForm)
- Interactive document dissection

### Planned features

- User management and groups/organisations
- Rule-based (automatic) tagging
- Self-learning tagging
- Additional document types
  - JPG, PNG, TIFF
  - Plain text
  - HTML
  - Email

### Features not planned

Since we want to keep our focus, the following features are not
planned to become a part of Pepa:

* Support for proprietary and/or very complex file formats. notably
  Microsoft Word, Excel, and Powerpoint. Neither are we planning to
  implement support for documents from LibreOffice or OpenOffice.
  Those formats are not well suited for archiving and good support
  would be too much effort.

Since Pepa is open source, you are of course free to implement those
features yourself.

## Project status

Pepa is currently in an early development stage which means that it's
not ready for production use, yet, as there will be frequent breaking
changes still. You are very welcome to give it a try, though, and we
are happy to receive feedback either via GitHub issues or
[by email via pepa@bevuta.com](mailto:pepa@bevuta.com). Note that
since there is no official release, yet, you will have to build Pepa
from sources to run it (see build instructions below).

## Screenshots

![Dashboard View](../screenshots/screenshots/dashboard.png?raw=true "Dashboard View")
![Document View](../screenshots/screenshots/document-view.png?raw=true "Document View")

## Installation

See [installation.md](doc/installation.md) in the doc/ directory.

## Icons

Icons found under the `resources/public/img/material/` folder are
based on the
["Material Design Icons"](https://github.com/google/material-design-icons/)
by Google and are released under an
[Attribution 4.0 International](http://creativecommons.org/licenses/by/4.0/)
license.

## License

Copyright © 2015 [bevuta IT GmbH](http://www.bevuta.com/)

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as
published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public
License along with this program, see [LICENSE](). If not, see
https://gnu.org/licenses/agpl.html.

In addition, as a special exception, the copyright holders of the
software give you permission to link its code with the following
libraries and distribute linked combinations including them:

* ch.qos.logback/logback-classic
* ch.qos.logback/logback-core
* cider/cider-nrepl
* cljs-tooling
* clout
* com.cemerick/piggieback
* com.facebook/react
* com.stuartsierra/dependency
* compliment
* compojure
* crypto-equality
* crypto-random
* garden
* instaparse
* io.clojure/liberator-transit
* liberator
* medley
* om
* org.clojure/clojure
* org.clojure/clojurescript
* org.clojure/core.async
* org.clojure/core.cache
* org.clojure/core.match
* org.clojure/core.memoize
* org.clojure/data.csv
* org.clojure/data.json
* org.clojure/data.priority-map
* org.clojure/java.classpath
* org.clojure/java.jdbc
* org.clojure/test.check
* org.clojure/tools.analyzer
* org.clojure/tools.analyzer.jvm
* org.clojure/tools.macro
* org.clojure/tools.namespace
* org.clojure/tools.nrepl
* org.clojure/tools.reader
* org.clojure/tools.trace
* org.tcrawley/dynapath
* prismatic/plumbing
* prismatic/schema
* ring-transit
* sablono
* secretary

If you modify this file, you may extend this exception to your version
of the file, but you are not obligated to do so. If you do not wish to
do so, delete this exception statement from your version.
