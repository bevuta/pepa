(defproject pepa "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [com.stuartsierra/component "0.2.3"]
                 [org.clojure/tools.namespace "0.2.10"]

                 ;; DB
                 [org.clojure/java.jdbc "0.3.6"]
                 [com.mchange/c3p0 "0.9.2.1"]
                 [org.postgresql/postgresql "9.3-1102-jdbc41"]

                 ;; Web
                 [org.immutant/web "2.0.0-beta2"]
                 [compojure "1.3.2"]
                 [ring/ring-devel "1.3.2"]
                 [com.cognitect/transit-clj "0.8.269"]
                 [ring-transit "0.1.3"]
                 [ring/ring-json "0.3.1"]
                 [hiccup "1.0.5"]
                 [liberator "0.12.2"]
                 [io.clojure/liberator-transit "0.3.0"]

                 ;; Utility
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/core.match "0.2.2"]

                 ;; E-Mail
                 [javax.mail/mail "1.4.7"
                  :exclusions [javax.activation/activation]]
                 [org.subethamail/subethasmtp "3.1.7"]]
  :plugins [[lein-cljsbuild "1.0.4"]]
  :jvm-opts ["-XX:+UseConcMarkSweepGC"
             "-XX:+CMSClassUnloadingEnabled"
             "-XX:MaxPermSize=128M"]
  :source-paths ["src/" "src-cljs/"]
  :main pepa.core
  :profiles {:repl {:plugins [[cider/cider-nrepl "0.8.2"]]
                    :repl-options {:timeout 300000
                                   :init-ns user}}
             ;; We store the cljs-deps here so they won't get added to the uberjar
             :provided {:dependencies [[org.clojure/clojurescript "0.0-2985"]
                                       [com.cemerick/piggieback "0.1.6-SNAPSHOT"]
                                       [com.cognitect/transit-cljs "0.8.205"]
                                       [org.omcljs/om "0.8.8" :exclusions [cljsjs/react]]
                                       [cljsjs/react-with-addons "0.12.2-7"]
                                       [org.clojars.the-kenny/nom "0.1.0"]
                                       [org.clojars.the-kenny/garden "1.3.0-SNAPSHOT"]
                                       [sablono "0.3.4" :exclusions [cljsjs/react]]
                                       [secretary "1.2.1"]
                                       [weasel "0.6.0-SNAPSHOT"]
                                       [the/parsatron "0.0.7"]
                                       [org.clojars.the-kenny/nom "0.1.0-SNAPSHOT"]]}}
  ;; Cljsbuild configuration. Also see profiles.clj
  :cljsbuild {:builds {:pepa {:source-paths ["src-cljs/"]
                              :compiler {:output-to "resources/public/pepa.js"
                                         :output-dir "resources/public/out/"
                                         :source-map "resources/public/pepa.js.map"
                                         :asset-path "out/"
                                         :main pepa.core
                                         :cache-analysis true
                                         :optimizations :none
                                         :pretty-print true}}}}
  :clean-targets ^{:protect false} [[:cljsbuild :builds :pepa :compiler :output-to]
                                    [:cljsbuild :builds :pepa :compiler :source-map]
                                    [:cljsbuild :builds :pepa :compiler :output-dir]
                                    :target-path :compile-path]
  :repositories [["Immutant incremental builds"
                  "http://downloads.immutant.org/incremental/"]])
