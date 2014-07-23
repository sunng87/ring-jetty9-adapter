(defproject info.sunng/ring-jetty9-adapter "0.7.1"
  :description "Ring adapter for jetty9, which supports websocket and spdy"
  :url "http://github.com/getaroom/ring-jetty9-adapter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [ring/ring-core "1.3.0"
                  :exclusions [javax.servlet/servlet-api]]
                 [ring/ring-servlet "1.3.0"
                  :exclusions [javax.servlet/servlet-api]]
                 [org.eclipse.jetty/jetty-server "9.2.1.v20140609"]
                 [org.eclipse.jetty.websocket/websocket-server "9.2.1.v20140609"]
                 [org.eclipse.jetty.websocket/websocket-servlet "9.2.1.v20140609"]]
  :deploy-repositories {"releases" :clojars})
