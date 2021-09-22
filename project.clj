(defproject re-frame-lint "0.1.3"
  :description "Linter to check re-frame handlers."
  :url "https://github.com/bgpworks/re-frame-lint"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/clojurescript "1.10.764"]
                 [org.clojure/tools.logging "1.1.0"]
                 [org.clojure/tools.cli "1.0.206"]]
  :main ^:skip-aot re-frame-lint.core
  :target-path "target/%s"
  :profiles {:dev {:global-vars {*warn-on-reflection* true}}
             :uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}}
  :cljfmt {:indents {cljs.analyzer.api/in-cljs-user [[:inner 0]]}})
