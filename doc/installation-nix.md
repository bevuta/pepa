## NixOS

Pepa comes with a NixOS container which makes setting up the
dependencies a breeze. First, create and start the `pepa-dev`
container like this:

    sudo nix/dev-container

This script is idempotent so you can run it whenever you want to start
the Pepa environment.  Use `nix/lein` and `nix/psql` for
`docs/installation-pepa.md`.
