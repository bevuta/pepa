(defproject pepa "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [com.stuartsierra/component "0.2.2"]
                 [org.clojure/tools.namespace "0.2.8"]

                 ;; DB
                 [org.clojure/java.jdbc "0.3.6"]
                 [com.mchange/c3p0 "0.9.2.1"]
                 [org.postgresql/postgresql "9.3-1102-jdbc41"]

                 ;; Web
                 [org.immutant/web "2.0.0-beta1"]
                 [compojure "1.3.1"]
                 [ring/ring-devel "1.3.2"]
                 [com.cognitect/transit-clj "0.8.259"]
                 [ring-transit "0.1.3"]
                 [hiccup "1.0.5"]
                 [liberator "0.12.2"]
                 [io.clojure/liberator-transit "0.3.0"]

                 ;; Utility
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/core.match "0.2.2"]

                 ;; E-Mail
                 [javax.mail/mail "1.4.7"
                  :exclusions [javax.activation/activation]]
                 [org.subethamail/subethasmtp "3.1.7"]

                 ;; Printing
                 [com.bevuta/lpd "0.1.0"]
                 [javax.jmdns/jmdns "3.4.1"]]
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
             :provided {:dependencies [[org.clojure/clojurescript "0.0-2665"]
                                       [com.cemerick/piggieback "0.1.5"]
                                       [com.cognitect/transit-cljs "0.8.199"]
                                       [org.om/om "0.8.0"]
                                       [garden "1.2.5"]
                                       [sablono "0.2.22"]
                                       [secretary "1.2.1"]
                                       [weasel "0.5.0"]
                                       [the/parsatron "0.0.7"]]}}
  ;; Cljsbuild configuration. Also see profiles.clj
  :cljsbuild {:builds {:pepa {:source-paths ["src-cljs/"]
                              :compiler {:output-to "resources/public/pepa.js"
                                         :output-dir "resources/public/out/"
                                         :source-map "resources/public/pepa.js.map"
                                         :cache-analysis true
                                         :optimizations :none
                                         :pretty-print true}}}}
  :clean-targets ^{:protect false} [[:cljsbuild :builds :pepa :compiler :output-to]
                                    [:cljsbuild :builds :pepa :compiler :source-map]
                                    [:cljsbuild :builds :pepa :compiler :output-dir]
                                    :target-path :compile-path])
