(def jetty-version "9.4.12.v20180830")
(defproject info.sunng/ring-jetty9-adapter "0.12.0-SNAPSHOT"
  :description "Ring adapter for jetty9, which supports websocket and spdy"
  :url "http://github.com/sunng87/ring-jetty9-adapter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [ring/ring-servlet "1.6.3"
                  :exclusions [javax.servlet/servlet-api]]
                 [org.eclipse.jetty/jetty-server ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-server ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-servlet ~jetty-version]
                 [org.eclipse.jetty.http2/http2-server ~jetty-version]
                 [org.eclipse.jetty/jetty-alpn-conscrypt-server ~jetty-version]
                 [org.conscrypt/conscrypt-openjdk "1.3.0" :classifier "linux-x86_64"]]
  :deploy-repositories {"releases" :clojars}
  :global-vars {*warn-on-reflection* true}
  :profiles {:example-http2-openjdk8 {:source-paths ["examples/"]
                                      :main ^:skip-aot rj9a.http2}
             :example-websocket {:source-paths ["examples/"]
                                 :main ^:skip-aot rj9a.websocket}
             :example-http {:source-paths ["examples/"]
                            :main ^:skip-aot rj9a.http}
             :example-async {:source-paths ["examples/"]
                             :main ^:skip-aot rj9a.async}})
