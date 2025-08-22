(def jetty-version "12.1.0")

(defproject info.sunng/ring-jetty9-adapter-http2 "0.2.0"
  :description "Ring adapter for jetty 9 and above, meta package for http2"
  :url "http://github.com/sunng87/ring-jetty9-adapter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :deploy-repositories {"releases" :clojars}
  :global-vars {*warn-on-reflection* true}
  :dependencies [[org.clojure/clojure "1.12.1"]
                 [org.eclipse.jetty.http2/jetty-http2-server ~jetty-version]
                 [org.eclipse.jetty/jetty-alpn-server ~jetty-version]
                 [org.eclipse.jetty/jetty-alpn-java-server ~jetty-version]])
