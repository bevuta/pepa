## Arch Linux

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
