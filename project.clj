(def jetty-version "10.0.6")

(defproject info.sunng/ring-jetty9-adapter "0.15.2-SNAPSHOT"
  :description "Ring adapter for jetty9, which supports websocket and spdy"
  :url "http://github.com/sunng87/ring-jetty9-adapter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [ring/ring-servlet "1.9.2"]
                 [org.eclipse.jetty/jetty-server ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-jetty-api ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-jetty-server ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-servlet ~jetty-version]
                 [org.eclipse.jetty.http2/http2-server ~jetty-version]
                 [org.eclipse.jetty/jetty-alpn-server ~jetty-version]]
  :deploy-repositories {"releases" :clojars}
  :global-vars {*warn-on-reflection* true}
  :jvm-args ["-Xmx128m"]
  :profiles {:dev {:dependencies [[clj-http "3.12.1"]
                                  #_[stylefruits/gniazdo "1.1.4"]]}
             :example-http2 {:source-paths ["examples/"]
                             :main ^:skip-aot rj9a.http2
                             :dependencies [[org.eclipse.jetty/jetty-alpn-java-server ~jetty-version]]}
             :example-websocket {:source-paths ["examples/"]
                                 :main ^:skip-aot rj9a.websocket}
             :example-http {:source-paths ["examples/"]
                            :main ^:skip-aot rj9a.http}
             :example-async {:source-paths ["examples/"]
                             :main ^:skip-aot rj9a.async}})
