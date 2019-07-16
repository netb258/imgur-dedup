(defproject imgur-dedup "0.1.0-SNAPSHOT"
  :description "This program goes through an imgur album and finds all duplicate images."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :resource-paths ["local-libs/ImagePHash.jar"]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/data.json "0.2.6"]
                 [clj-http "3.9.1"]]
  :main ^:skip-aot imgur-dedup.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
