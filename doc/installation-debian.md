## Debian Jessie

Install [Leiningen](https://github.com/technomancy/leiningen/) on your
system (see instructions on their website). All other dependencies are
available via `apt-get`.

    sudo apt-get update
    sudo apt-get install openjdk-7-jre-headless pdftk postgresql poppler-utils cuneiform tesseract-ocr tesseract-ocr-eng tesseract-ocr-deu

Next, configure PostgreSQL according to the instructions in the
[Debian wiki](https://wiki.debian.org/PostgreSql).  Don't forget
creating a `pepa` system account under which Pepa will run.  Follow
the remaining instructions logged in as this user, e.g. `sudo -u pepa -s`.
