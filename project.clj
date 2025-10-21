(def jetty-version "12.1.3")
(def ring-version "1.15.3")

(defproject info.sunng/ring-jetty9-adapter "0.39.1-SNAPSHOT"
  :description "Ring adapter for jetty9, which supports websocket, http/2 and http/3"
  :url "http://github.com/sunng87/ring-jetty9-adapter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.12.3"]
                 [org.ring-clojure/ring-core-protocols ~ring-version]
                 [org.ring-clojure/ring-websocket-protocols ~ring-version]
                 [info.sunng/ring-jetty9-adapter-http2 "0.2.2" :optional true]
                 [info.sunng/ring-jetty9-adapter-http3 "0.7.2" :optional true]
                 [org.eclipse.jetty/jetty-server ~jetty-version]
                 [org.eclipse.jetty/jetty-util ~jetty-version]
                 [org.eclipse.jetty.websocket/jetty-websocket-jetty-api ~jetty-version]
                 [org.eclipse.jetty.websocket/jetty-websocket-jetty-server ~jetty-version]]
  :deploy-repositories {"releases" :clojars}
  :global-vars {*warn-on-reflection* true}
  :jvm-args ["-Xmx128m"]
  :aot [ring.adapter.jetty9.handlers.sync
        ring.adapter.jetty9.handlers.async]
  :profiles {:dev {:dependencies [[clj-http "3.13.0"]
                                  [less-awful-ssl "1.0.7"]
                                  [org.eclipse.jetty/jetty-slf4j-impl ~jetty-version]
                                  #_[stylefruits/gniazdo "1.1.4"]]
                   :resource-paths ["dev-resources"]}
             :example-http2 {:source-paths ["examples/"]
                             :dependencies [[org.eclipse.jetty/jetty-slf4j-impl ~jetty-version]]
                             :resource-paths ["dev-resources/"]
                             :main ^:skip-aot rj9a.http2}
             :example-http3 {:source-paths ["examples/"]
                             :dependencies [[org.eclipse.jetty/jetty-slf4j-impl ~jetty-version]]
                             :resource-paths ["dev-resources/"]
                             :main ^:skip-aot rj9a.http3}
             :example-websocket {:source-paths ["examples/"]
                                 :dependencies [[org.eclipse.jetty/jetty-slf4j-impl ~jetty-version]
                                                [ring/ring-core ~ring-version]]
                                 :main ^:skip-aot rj9a.websocket}
             :example-http {:source-paths ["examples/"]
                            :dependencies [[org.eclipse.jetty/jetty-slf4j-impl ~jetty-version]]
                            :main ^:skip-aot rj9a.http}
             :example-async {:source-paths ["examples/"]
                             :dependencies [[org.eclipse.jetty/jetty-slf4j-impl ~jetty-version]]
                             :main ^:skip-aot rj9a.async}
             :uberjar {:aot :all}})
