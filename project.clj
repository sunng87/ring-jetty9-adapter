(def jetty-version "12.0.0")

(defproject info.sunng/ring-jetty9-adapter "0.30.0-SNAPSHOT"
  :description "Ring adapter for jetty9, which supports websocket and spdy"
  :url "http://github.com/sunng87/ring-jetty9-adapter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [ring/ring-core "1.10.0" :exclusions [commons-io]]
                 [info.sunng/ring-jetty9-adapter-http3 "0.4.0-SNAPSHOT" :optional true]
                 [org.eclipse.jetty/jetty-server ~jetty-version]
                 [org.eclipse.jetty.websocket/jetty-websocket-jetty-api ~jetty-version]
                 [org.eclipse.jetty.websocket/jetty-websocket-jetty-server ~jetty-version]
                 [org.eclipse.jetty.http2/jetty-http2-server ~jetty-version]
                 [org.eclipse.jetty/jetty-alpn-server ~jetty-version]
                 [org.eclipse.jetty/jetty-alpn-java-server ~jetty-version]]
  :deploy-repositories {"releases" :clojars}
  :global-vars {*warn-on-reflection* true}
  :jvm-args ["-Xmx128m"]
  :aot [ring.adapter.jetty9.handlers.sync
        #_ring.adapter.jetty9.handlers.async]
  :profiles {:dev {:dependencies [[clj-http "3.12.3"]
                                  [less-awful-ssl "1.0.6"]
                                  [org.eclipse.jetty/jetty-slf4j-impl ~jetty-version]
                                  #_[stylefruits/gniazdo "1.1.4"]]
                   :resource-paths ["dev-resources"]}
             :example-http2 {:source-paths ["examples/"]
                             :main ^:skip-aot rj9a.http2}
             :example-http3 {:source-paths ["examples/"]
                             :resource-paths ["dev-resources/"]
                             :main ^:skip-aot rj9a.http3}
             :example-websocket {:source-paths ["examples/"]
                                 :main ^:skip-aot rj9a.websocket}
             :example-http {:source-paths ["examples/"]
                            :main ^:skip-aot rj9a.http}
             :example-async {:source-paths ["examples/"]
                             :main ^:skip-aot rj9a.async}
             :uberjar {:aot :all}})
