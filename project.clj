(def jetty-version "9.3.1.v20150714")
(defproject info.sunng/ring-jetty9-adapter "0.9.0-SNAPSHOT"
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
                 [org.eclipse.jetty.alpn/alpn-api "1.1.2.v20150522"]
                 [org.eclipse.jetty/jetty-alpn-server ~jetty-version]]
  :deploy-repositories {"releases" :clojars}
  :global-vars {*warn-on-reflection* true}
  :profiles {:example {:source-paths ["examples/"]
                       :main ^:skip-aot core
                       :boot-dependencies [[org.mortbay.jetty.alpn/alpn-boot "8.1.3.v20150130"]]
                       :plugins [[info.sunng/lein-bootclasspath-deps "0.1.1"]]}
             :uberjar {:aot :all
                       :uberjar-name "server.jar"}})
