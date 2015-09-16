## Debian Jessie

Install [Leiningen](https://github.com/technomancy/leiningen/) on your
system (see instructions on their website). All other dependencies are
available via `apt-get`.

    sudo apt-get update
    sudo apt-get install openjdk-7-jre-headless pdftk postgresql poppler-utils cuneiform tesseract-ocr tesseract-ocr-eng tesseract-ocr-deu

Next, configure PostgreSQL according to the instructions in the
[Debian wiki](https://wiki.debian.org/PostgreSql).

Create a role and database for Pepa:

    sudo -u postgres psql -c "CREATE USER pepa"
    sudo -u postgres psql -c "CREATE DATABASE pepa OWNER pepa"

To allow Pepa to access the database, you need to set up
authorization.  PostgreSQL on Debian is configured to accept
connections from users who have the same name as the role.  Therefore
it is sufficient on such a system to create a `pepa` system account
under which Pepa will run and following the remaining instructions
logged in as this user, e.g. `sudo -u pepa -s`.

Alternatively add the following line to the beginning of `pg_hba.conf`
to allow connections from localhost for the `pepa` role:

    host pepa pepa 127.0.0.1/32 trust

Restart the server:

    sudo -u postgres pg_ctlcluster restart
