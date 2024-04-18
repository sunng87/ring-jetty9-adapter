(def jetty-version "12.0.8")

(defproject info.sunng/ring-jetty9-adapter "0.33.0"
  :description "Ring adapter for jetty9, which supports websocket and spdy"
  :url "http://github.com/sunng87/ring-jetty9-adapter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.11.2"]
                 [org.ring-clojure/ring-core-protocols "1.12.1"]
                 [org.ring-clojure/ring-websocket-protocols "1.12.1"]
                 [info.sunng/ring-jetty9-adapter-http3 "0.5.1" :optional true]
                 [org.eclipse.jetty/jetty-server ~jetty-version]
                 [org.eclipse.jetty/jetty-util ~jetty-version]
                 [org.eclipse.jetty.websocket/jetty-websocket-jetty-api ~jetty-version]
                 [org.eclipse.jetty.websocket/jetty-websocket-jetty-server ~jetty-version]
                 [org.eclipse.jetty.http2/jetty-http2-server ~jetty-version]
                 [org.eclipse.jetty/jetty-alpn-server ~jetty-version]
                 [org.eclipse.jetty/jetty-alpn-java-server ~jetty-version]]
  :deploy-repositories {"releases" :clojars}
  :global-vars {*warn-on-reflection* true}
  :jvm-args ["-Xmx128m"]
  :aot [ring.adapter.jetty9.handlers.sync
        ring.adapter.jetty9.handlers.async]
  :profiles {:dev {:dependencies [[clj-http "3.12.3"]
                                  [less-awful-ssl "1.0.6"]
                                  [org.eclipse.jetty/jetty-slf4j-impl ~jetty-version]
                                  #_[stylefruits/gniazdo "1.1.4"]]
                   :resource-paths ["dev-resources"]}
             :example-http2 {:source-paths ["examples/"]
                             :dependencies [[org.eclipse.jetty/jetty-slf4j-impl ~jetty-version]]
                             :main ^:skip-aot rj9a.http2}
             :example-http3 {:source-paths ["examples/"]
                             :dependencies [[org.eclipse.jetty/jetty-slf4j-impl ~jetty-version]]
                             :resource-paths ["dev-resources/"]
                             :main ^:skip-aot rj9a.http3}
             :example-websocket {:source-paths ["examples/"]
                                 :dependencies [[org.eclipse.jetty/jetty-slf4j-impl ~jetty-version]
                                                [ring/ring-core "1.12.1"]]
                                 :main ^:skip-aot rj9a.websocket}
             :example-http {:source-paths ["examples/"]
                            :dependencies [[org.eclipse.jetty/jetty-slf4j-impl ~jetty-version]]
                            :main ^:skip-aot rj9a.http}
             :example-async {:source-paths ["examples/"]
                             :dependencies [[org.eclipse.jetty/jetty-slf4j-impl ~jetty-version]]
                             :main ^:skip-aot rj9a.async}
             :uberjar {:aot :all}})
