(defproject info.sunng/ring-jetty9-adapter "0.8.0-SNAPSHOT"
  :description "Ring adapter for jetty9, which supports websocket and spdy"
  :url "http://github.com/getaroom/ring-jetty9-adapter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [ring/ring-servlet "1.3.2"
                  :exclusions [javax.servlet/servlet-api]]
                 [org.eclipse.jetty/jetty-server "9.2.6.v20141205"]
                 [org.eclipse.jetty.websocket/websocket-server "9.2.6.v20141205"]
                 [org.eclipse.jetty.websocket/websocket-servlet "9.2.6.v20141205"]]
  :deploy-repositories {"releases" :clojars})
