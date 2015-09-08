## Pepa

Using the pepa user role, import the database schema:

    lein pepa schema | psql -U pepa pepa

Create a config file by copying the sample config file into the proper
place:

    cp resources/config.sample.clj config.clj

Edit the config file to mention the correct host by replacing
`<db-host>`.

Next, compile the ClojureScript:

    lein cljsbuild once

If you intend to hack on the frontend code, you might want to run
`lein cljsbuild auto` or `lein figwheel` instead. Finally, start the
REPL:

    lein repl

Start the server by calling the `(go)` function in the `user`
namespace. Alternatively, if you don't need a REPL, you can just use
`lein run` to start the server.
