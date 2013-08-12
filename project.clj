(defproject info.sunng/ring-jetty9-adapter "0.1.0"
  :description "Ring adapter for jetty9, which supports websocket and spdy"
  :url "http://github.com/getaroom/ring-jetty9-adapter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [ring/ring-core "1.2.0-beta2"
                  :exclusions [javax.servlet/servlet-api]]
                 [ring/ring-servlet "1.2.0-beta2"
                  :exclusions [javax.servlet/servlet-api]]
                 [org.eclipse.jetty/jetty-server "9.0.4.v20130625"]
                 [org.eclipse.jetty/jetty-jmx "9.0.4.v20130625"]
                 [org.eclipse.jetty.websocket/websocket-server "9.0.4.v20130625"]
                 [org.eclipse.jetty.websocket/websocket-servlet "9.0.4.v20130625"]])
