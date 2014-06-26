(defproject info.sunng/ring-jetty9-adapter "0.6.2-SNAPSHOT"
  :description "Ring adapter for jetty9, which supports websocket and spdy"
  :url "http://github.com/getaroom/ring-jetty9-adapter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [ring/ring-core "1.2.1"
                  :exclusions [javax.servlet/servlet-api]]
                 [ring/ring-servlet "1.2.1"
                  :exclusions [javax.servlet/servlet-api]]
                 [org.eclipse.jetty/jetty-server "9.1.3.v20140225"]
                 [org.eclipse.jetty.websocket/websocket-server "9.1.3.v20140225"]
                 [org.eclipse.jetty.websocket/websocket-servlet "9.1.3.v20140225"]]
  :deploy-repositories {"releases" :clojars})
