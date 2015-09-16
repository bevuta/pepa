## Arch Linux

Install some packages via pacman as root:

    pacman -S jdk8-openjdk postgresql poppler cuneiform tesseract tesseract-data-eng tesseract-data-deu git

# pdftk-installation

Install pdf-tk (or pdftk-bin) from AUR. If you don't know how, here are the instructions:

As root:

    pacman -S --needed base-devel

as a user which is allowed to install packages via sudo:

    mkdir /tmp/pdftk-build
    cd /tmp/pdftk-build
    git clone https://aur.archlinux.org/libgcj.git
    cd libgcj
    makepkg -sri
    cd ..
    rm -rf libgcj
    git clone https://aur.archlinux.org/pdftk-bin.git
    cd pdftk-bin
    makepkg -sri
    cd ..
    rm -rf pdftk-bin
    cd ..
    rmdir pdftk-build
    cd

# PostgreSQL initialization

If you are unexperienced with postgresql and have it freshly installed, run

    initdb --locale en_US.UTF-8 -E UTF8 -D '/var/lib/postgres/data'

as user postgres. Then run (as root):

    systemctl enable postgresql.service

# PostgreSQL configuration

If postgresql isn't running, start it with

    systemctl start postgresql.service

as root. Now, to have a pepa user and database in your postgresql database, run

    psql -c "CREATE USER pepa"
    psql -c "CREATE DATABASE pepa OWNER pepa"

as user postgres.

Now, as root again, run

    echo host pepa pepa 127.0.0.1/32 trust >> /var/lib/postgres/data/pg_hba.conf

Now, restart postgres (as root):

    systemctl restart postgresql.service

Now, you can follow the installation-pepa.md for further instructions.
