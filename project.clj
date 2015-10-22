(defproject pepa "0.1.0-SNAPSHOT"
  :description "Pepa: Document Management System"
  :url "https://github.com/bevuta/pepa"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0-RC1"]
                 [com.stuartsierra/component "0.2.3"]
                 [org.clojure/tools.namespace "0.2.10"]

                 ;; DB
                 [org.clojure/java.jdbc "0.4.1"]
                 [com.mchange/c3p0 "0.9.5"]
                 [org.postgresql/postgresql "9.3-1102-jdbc41"]

                 ;; Web
                 [ring/ring-core "1.4.0"]
                 [ring/ring-json "0.4.0"]
                 
                 [org.immutant/web "2.1.0"]
                 [org.immutant/scheduling "2.1.0"]

                 [liberator "0.13"]
                 [compojure "1.4.0"]
                 [hiccup "1.0.5"]
                 
                 [com.cognitect/transit-clj "0.8.281"]
                 [io.clojure/liberator-transit "0.3.0"]
                 [org.clojure/data.json "0.2.6"]

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
  :plugins [[lein-cljsbuild "1.0.5"]]
  :jvm-opts ["-XX:+UseConcMarkSweepGC"
             "-XX:+CMSClassUnloadingEnabled"
             "-XX:MaxPermSize=128M"]
  :source-paths ["src/" "src-cljs/"]
  :main pepa.core
  :cljsbuild {:builds {:pepa {:source-paths ["src" "src-cljs"]
                              :compiler {:output-to "resources/public/pepa.js"
                                         :output-dir "resources/public/out"
                                         :asset-path "out"
                                         :main pepa.core
                                         :cache-analysis true}}}}
  :profiles {:repl {:plugins [[cider/cider-nrepl "0.9.0"]]
                    :repl-options {:timeout 300000
                                   :init-ns user}}
             :dev {:dependencies [[lein-figwheel "0.3.7" :exclusions [cider/cider-nrepl]]]
                   :plugins [[lein-figwheel "0.3.3" :exclusions [cider/cider-nrepl]]]
                   :cljsbuild {:builds {:pepa {:source-paths ["dev"]
                                               :figwheel {:on-jsload "pepa.core/on-js-reload"}
                                               :compiler {:source-map "resources/public/pepa.js.map"
                                                          :optimizations :none
                                                          :pretty-print true}}}}}
             ;; We store the cljs-deps here so they won't get added to the uberjar
             :provided {:dependencies [[org.clojure/clojurescript "1.7.107"]
                                       [com.cognitect/transit-cljs "0.8.220"]
                                       [org.omcljs/om "0.9.0"]
                                       [org.clojars.the-kenny/nom "0.1.1"]
                                       [org.clojars.the-kenny/garden "1.3.0-SNAPSHOT"
                                        :exclusions [org.clojure/tools.reader]]
                                       [sablono "0.3.6" :exclusions [cljsjs/react]]
                                       [secretary "1.2.3"]
                                       [the/parsatron "0.0.7"]]}
             ;; Advanced Cljs Compilation
             :advanced [:provided
                        {:cljsbuild {:builds {:pepa {:compiler {:optimizations :advanced
                                                                :elide-asserts true
                                                                :pretty-print false}}}}}]
             :uberjar [:advanced
                       {:aot [pepa.core
                              ;; Hack to fix NoClassDefFoundErrors when uberjar-ing
                              com.stuartsierra.component
                              com.stuartsierra.dependency]
                        :hooks [leiningen.cljsbuild]
                        :jar-exclusions [#"^public/out"]}]}
  
  :clean-targets ^{:protect false} [[:cljsbuild :builds :pepa :compiler :output-to]
                                    [:cljsbuild :builds :pepa :compiler :output-dir]
                                    :target-path :compile-path])
