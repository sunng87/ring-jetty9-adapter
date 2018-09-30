(def jetty-version "9.4.12.v20180830")

(defproject info.sunng/ring-jetty9-adapter "0.12.0-SNAPSHOT"
  :description "Ring adapter for jetty9, which supports websocket and spdy"
  :url "http://github.com/sunng87/ring-jetty9-adapter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [ring/ring-servlet "1.7.0"
                  :exclusions [javax.servlet/servlet-api]]
                 [org.eclipse.jetty/jetty-server ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-server ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-servlet ~jetty-version]
                 [org.eclipse.jetty.http2/http2-server ~jetty-version]
                 [org.eclipse.jetty/jetty-alpn-server ~jetty-version]
                 [org.eclipse.jetty/jetty-alpn-conscrypt-server ~jetty-version]
                 [org.conscrypt/conscrypt-openjdk-uber "1.3.0"]]
  :deploy-repositories {"releases" :clojars}
  :global-vars {*warn-on-reflection* true}
  :profiles {:example-http2 {:source-paths ["examples/"]
                             :main ^:skip-aot rj9a.http2}
             ;; for openjdk8
             :example-http2-openjdk8 {:source-paths ["examples/"]
                                      :main ^:skip-aot rj9a.http2
                                      :dependencies ^:replace [[org.clojure/clojure "1.9.0"]
                                                               [ring/ring-servlet "1.7.0"
                                                                :exclusions [javax.servlet/servlet-api]]
                                                               [org.eclipse.jetty/jetty-server ~jetty-version]
                                                               [org.eclipse.jetty.websocket/websocket-server ~jetty-version]
                                                               [org.eclipse.jetty.websocket/websocket-servlet ~jetty-version]
                                                               [org.eclipse.jetty.http2/http2-server ~jetty-version]
                                                               ;; OpenJDK8 ALPN
                                                               [org.eclipse.jetty.alpn/alpn-api "1.1.3.v20160715"]
                                                               [org.eclipse.jetty/jetty-alpn-server ~jetty-version]
                                                               [org.eclipse.jetty/jetty-alpn-openjdk8-server ~jetty-version]]
                                      :boot-dependencies [[org.mortbay.jetty.alpn/alpn-boot "8.1.12.v20180117"
                                                           :prepend true]]
                                      :plugins [[info.sunng/lein-bootclasspath-deps "0.3.0"]]}
             ;; for jdk9+
             :example-http2-jdk9 {:source-paths ["examples/"]
                                  :main ^:skip-aot rj9a.http2
                                  :dependencies ^:replace [[org.clojure/clojure "1.9.0"]
                                                           [ring/ring-servlet "1.7.0"
                                                            :exclusions [javax.servlet/servlet-api]]
                                                           [org.eclipse.jetty/jetty-server ~jetty-version]
                                                           [org.eclipse.jetty.websocket/websocket-server ~jetty-version]
                                                           [org.eclipse.jetty.websocket/websocket-servlet ~jetty-version]
                                                           [org.eclipse.jetty.http2/http2-server ~jetty-version]
                                                           ;; JDK9 ALPN
                                                           [org.eclipse.jetty.alpn/alpn-api "1.1.3.v20160715"]
                                                           [org.eclipse.jetty/jetty-alpn-server ~jetty-version]
                                                           [org.eclipse.jetty/jetty-alpn-java-server ~jetty-version]]}
             :example-websocket {:source-paths ["examples/"]
                                 :main ^:skip-aot rj9a.websocket}
             :example-http {:source-paths ["examples/"]
                            :main ^:skip-aot rj9a.http}
             :example-async {:source-paths ["examples/"]
                             :main ^:skip-aot rj9a.async}})
