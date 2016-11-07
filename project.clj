(def jetty-version "9.3.14.v20161028")
(defproject info.sunng/ring-jetty9-adapter "0.9.6-SNAPSHOT"
  :description "Ring adapter for jetty9, which supports websocket and spdy"
  :url "http://github.com/getaroom/ring-jetty9-adapter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[ring/ring-servlet "1.4.0"
                  :exclusions [javax.servlet/servlet-api]]
                 [org.eclipse.jetty/jetty-server ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-server ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-servlet ~jetty-version]
                 [org.eclipse.jetty.http2/http2-server ~jetty-version]
                 [org.eclipse.jetty.alpn/alpn-api "1.1.3.v20160715"]
                 [org.eclipse.jetty/jetty-alpn-server ~jetty-version]]
  :deploy-repositories {"releases" :clojars}
  :global-vars {*warn-on-reflection* true}
  :profiles {:example-http2 {:source-paths ["examples/"]
                             :main ^:skip-aot rj9a.http2
                             :boot-dependencies [[org.mortbay.jetty.alpn/alpn-boot "8.1.7.v20160121"
                                                  :prepend true]]
                             :plugins [[info.sunng/lein-bootclasspath-deps "0.2.0"]]}
             :example-websocket {:source-paths ["examples/"]
                                 :main ^:skip-aot rj9a.websocket}})
