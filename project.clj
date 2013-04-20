(defproject info.sunng/ring-jetty9-adapter "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [ring/ring-core "1.2.0-beta2"
                  :exclusions [javax.servlet/servlet-api]]
                 [ring/ring-servlet "1.2.0-beta2"
                  :exclusions [javax.servlet/servlet-api]]
                 [org.eclipse.jetty/jetty-server "9.0.2.v20130417"]
                 [org.eclipse.jetty.websocket/websocket-server "9.0.2.v20130417"]
                 [org.eclipse.jetty.websocket/websocket-servlet "9.0.2.v20130417"]])
