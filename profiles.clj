{
 ;; Advanced Cljs Compilation
 :advanced-test [:provided
                 {:cljsbuild {:builds {:pepa {:compiler {:optimizations :advanced
                                                         :pretty-print true
                                                         :pseudo-names true
                                                         :source-map nil}}}}}]
 :uberjar [:provided
           {:aot [pepa.core
                  ;; Hack to fix NoClassDefFoundErrors when uberjar-ing
                  com.stuartsierra.component
                  com.stuartsierra.dependency]
            ;; :omit-source true
            :hooks [leiningen.cljsbuild]
            :jar-exclusions [#"^public/out"]
            :cljsbuild {:builds {:pepa {:compiler {:optimizations :advanced
                                                   :elide-asserts true
                                                   :pretty-print false
                                                   :source-map nil}}}}}]}
