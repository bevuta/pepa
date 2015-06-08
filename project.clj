(defproject pepa "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0-RC1"]
                 [com.stuartsierra/component "0.2.3"]
                 [org.clojure/tools.namespace "0.2.10"]

                 ;; DB
                 [org.clojure/java.jdbc "0.3.7"]
                 [com.mchange/c3p0 "0.9.5"]
                 [org.postgresql/postgresql "9.3-1102-jdbc41"]

                 ;; Web
                 [ring/ring-core "1.3.2"]
                 [ring/ring-json "0.3.1"]
                 
                 [org.immutant/web "2.0.1"]
                 [liberator "0.13"]
                 [compojure "1.3.4"]
                 [hiccup "1.0.5"]
                 
                 [com.cognitect/transit-clj "0.8.271"]
                 [io.clojure/liberator-transit "0.3.0"]
                 [org.clojure/data.json "0.2.6"]
                 [com.cemerick/friend "0.2.1"
                  :exclusions [org.clojure/core.cache]]

                 ;; Utility
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/core.match "0.2.2"]

                 ;; HACK: Include tools.reader as we're excluding it
                 ;; globally to prevent libraries pulling in old
                 ;; versions of it
                 [org.clojure/tools.reader "0.9.2"]

                 ;; Logging
                 [org.clojure/tools.logging "0.3.1"]
                 [ch.qos.logback/logback-classic "1.1.3"]

                 ;; E-Mail
                 [javax.mail/mail "1.4.7"
                  :exclusions [javax.activation/activation]]
                 [org.subethamail/subethasmtp "3.1.7"
                  :exclusions [org.slf4j/slf4j-api]]

                 ;; Printing
                 [com.bevuta/lpd "0.1.0-SNAPSHOT"]
                 [javax.jmdns/jmdns "3.4.1"]]
  :exclusions [org.clojure/data.json
               log4j/log4j
               org.clojure/tools.reader]
  :plugins [[lein-cljsbuild "1.0.4"]]
  :jvm-opts ["-XX:+UseConcMarkSweepGC"
             "-XX:+CMSClassUnloadingEnabled"
             "-XX:MaxPermSize=128M"]
  :source-paths ["src/" "src-cljs/"]
  :main pepa.core
  :profiles {:repl {:plugins [[cider/cider-nrepl "0.9.0-SNAPSHOT"]]
                    :repl-options {:timeout 300000
                                   :init-ns user}}
             :dev {:dependencies [[com.cemerick/piggieback "0.2.1"]
                                  [org.clojure/tools.nrepl"0.2.10"]]
                   
                   :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}
             ;; We store the cljs-deps here so they won't get added to the uberjar
             :provided {:dependencies [[org.clojure/clojurescript "0.0-3297"]
                                       [com.cognitect/transit-cljs "0.8.215"]
                                       [org.omcljs/om "0.8.8"]
                                       [org.clojars.the-kenny/nom "0.1.1"]
                                       [org.clojars.the-kenny/garden "1.3.0-SNAPSHOT"
                                        :exclusions [org.clojure/tools.reader]]
                                       [sablono "0.3.4" :exclusions [cljsjs/react]]
                                       [secretary "1.2.3"]
                                       [the/parsatron "0.0.7"]

                                       [org.clojars.the-kenny/weasel "0.7.0-SNAPSHOT"]]}}
  ;; Cljsbuild configuration. Also see profiles.clj
  :cljsbuild {:builds {:pepa {:source-paths ["src/" "src-cljs/"]
                              :compiler {:output-to "resources/public/pepa.js"
                                         :output-dir "resources/public/out/"
                                         :source-map "resources/public/pepa.js.map"
                                         :asset-path "out/"
                                         :main pepa.core
                                         :cache-analysis true
                                         :optimizations :none
                                         :pretty-print true}}}}
  :clean-targets ^{:protect false} [[:cljsbuild :builds :pepa :compiler :output-to]
                                    [:cljsbuild :builds :pepa :compiler :output-dir]
                                    :target-path :compile-path])
