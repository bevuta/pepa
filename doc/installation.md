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

The following sections will run you through the process of setting up
a working environment to run and hack on Pepa for various target
platforms. It is assumed that the current working directory is the
project root, so:

    cd pepa

And you are good to go.

### NixOS

Pepa comes with a NixOS container which makes setting up the
dependencies a breeze. First, create and start the `pepa-dev`
container like this:

    sudo nix/dev-container

This script is idempotent so you can run it whenever you want to start
the Pepa environment. Now we need to create a `config.clj` so Pepa
knows where to find the PostgreSQL database server. Since it is
running inside the container, we can programmatically generate the
file from `resources/config.sample.clj` like this:

    sed "s,<db-host>,$(nixos-container show-ip pepa-dev)," resources/config.sample.clj > config.clj

Next, compile the ClojureScript:

    nix/lein cljsbuild once

If you intend to hack on the frontend code, you might want to run
`nix/lein cljsbuild auto` or `nix/lein figwheel` instead. Finally,
start the REPL:

    nix/lein repl

And you are set. Start the server by calling the `(go)` function in
the `user` namespace. Alternatively, if you don't need a REPL, you can
just use `nix/lein run` to start the server. The `nix/lein` script
will start Leinignen in a `nix-shell` environment which contains all
necessary (non-Java) dependencies.

Note that you can use `nix/psql` to convenietly open a `psql` shell
for the PostgreSQL instance running inside the container at any time.


### Arch Linux

Install [Leiningen](https://github.com/technomancy/leiningen/) on your
system (see instructions on their website). All other dependencies are
available via `pacman`.

    sudo pacman -S jdk8-openjdk pdftk postgresql poppler cuneiform tesseract tesseract-data-eng tesseract-data-deu

Next,
[configure PostgreSQL according to the instructions in the Arch Linux wiki](https://wiki.archlinux.org/index.php/PostgreSQL)
and add a line to the `pg_hba.conf` for the `pepa` user, e.g.:

    sudo sh -c 'echo host pepa pepa 127.0.0.1/32 trust >> /var/lib/postgres/data/pg_hba.conf'

Now (re-)start the server:

    sudo systemctl restart postgresql

Create a role and database for Pepa:

    sudo -u postgres psql -c "CREATE USER pepa"
    sudo -u postgres psql -c "CREATE DATABASE pepa OWNER pepa"

Using that new role, import the database schema:

    psql -h localhost -U pepa pepa < resources/schema.sql

Create a config file (`pepa` is the default role and database name so
we only need to set the hostname):

    sed "s,<db-host>,localhost," resources/config.sample.clj > config.clj

Next, compile the ClojureScript:

    lein cljsbuild once

If you intend to hack on the frontend code, you might want to run
`lein cljsbuild auto` or `lein figwheel` instead. Finally, start the
REPL:

    lein repl

Start the server by calling the `(go)` function in the `user`
namespace. Alternatively, if you don't need a REPL, you can just use
`lein run` to start the server.
