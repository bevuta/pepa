{
 ;; Advanced Cljs Compilation
 :advanced [:provided
            {:cljsbuild {:builds {:pepa {:source-paths ["src" "src-cljs"]
                                         :compiler {:optimizations :advanced
                                                    :pretty-print false
                                                    :source-map nil}
                                         :figwheel false}}}}]
 :uberjar [:provided
           {:aot [pepa.core
                  ;; Hack to fix NoClassDefFoundErrors when uberjar-ing
                  com.stuartsierra.component
                  com.stuartsierra.dependency]
            :hooks [leiningen.cljsbuild]
            :jar-exclusions [#"^public/out"]
            :cljsbuild {:builds {:pepa {:source-paths ["src" "src-cljs"]
                                        :compiler {:optimizations :advanced
                                                   :elide-asserts true
                                                   :pretty-print false
                                                   :source-map nil}
                                        :figwheel false}}}}]}
