{
 ;; Advanced Cljs Compilation
 :release {:cljsbuild {:builds {:pepa {:compiler {:preamble ["react/react.min.js"]
                                                  :optimizations :advanced
                                                  :pretty-print false
                                                  :source-map nil}}}}}
 :uberjar {:aot [pepa.core
                 ;; Hack to fix NoClassDefFoundErrors when uberjar-ing
                 com.stuartsierra.component
                 com.stuartsierra.dependency]
           ;; :omit-source true
           :hooks [leiningen.cljsbuild]
           :jar-exclusions [#"^public/out"
                            #"^public/react-0.12.2.js"]
           :cljsbuild {:builds {:pepa {:compiler {:preamble ["react/react.min.js"]
                                                  :optimizations :advanced
                                                  :elide-asserts true
                                                  :pretty-print false
                                                  :source-map nil}}}}}}
