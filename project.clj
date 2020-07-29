(def jetty-version "9.4.30.v20200611")

(defproject info.sunng/ring-jetty9-adapter "0.14.1-SNAPSHOT"
  :description "Ring adapter for jetty9, which supports websocket and spdy"
  :url "http://github.com/sunng87/ring-jetty9-adapter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [ring/ring-servlet "1.8.1"
                  :exclusions [javax.servlet/servlet-api]]
                 [org.eclipse.jetty/jetty-server ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-server ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-servlet ~jetty-version]
                 [org.eclipse.jetty.http2/http2-server ~jetty-version]
                 [org.eclipse.jetty/jetty-alpn-server ~jetty-version]]
  :deploy-repositories {"releases" :clojars}
  :global-vars {*warn-on-reflection* true}
  :jvm-args ["-Xmx128m"]
  :profiles {;; for openjdk8
             :example-http2-openjdk8 {:source-paths ["examples/"]
                                      :main ^:skip-aot rj9a.http2
                                      :dependencies [[org.eclipse.jetty/jetty-alpn-openjdk8-server ~jetty-version]]}
             ;; for jdk9+
             :example-http2 {:source-paths ["examples/"]
                             :main ^:skip-aot rj9a.http2
                             :dependencies [[org.eclipse.jetty/jetty-alpn-java-server ~jetty-version]]}
             :example-websocket {:source-paths ["examples/"]
                                 :main ^:skip-aot rj9a.websocket}
             :example-http {:source-paths ["examples/"]
                            :main ^:skip-aot rj9a.http}
             :example-async {:source-paths ["examples/"]
                             :main ^:skip-aot rj9a.async}})
