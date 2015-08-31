## Overview

- [./installation-arch.md](installation-arch.md)
- [./installation-debian.md](installation-debian.md)
- [./installation-nix.md](installation-nix.md)
- [./installation-pepa.md](installation-pepa.md)

## Build instructions

To build and run Pepa on your system, you'll need Java (at least
version 7), [Leinigen](https://github.com/technomancy/leiningen/),
[PostgreSQL](http://www.postgresql.org/),
[Poppler](http://poppler.freedesktop.org/),
[PDFtk](http://www.pdfhacks.com/pdftk). For OCR you'll need
[CuneiForm](https://launchpad.net/cuneiform-linux) and/or
[Tesseract-OCR](https://code.google.com/p/tesseract-ocr/).

Obtain the source via `git` like this:

    git clone git@github.com:bevuta/pepa.git

Install the dependencies, configure PostgreSQL and edit the config
file as described in either of `installation-arch.md`,
`installation-debian.md` or `installation-nix.md`.  Proceed with
`installation-pepa.md` for OS-independent instructions.
