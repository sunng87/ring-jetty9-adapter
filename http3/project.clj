(def jetty-version "12.1.0")

(defproject info.sunng/ring-jetty9-adapter-http3 "0.7.0"
  :description "Ring adapter for jetty 9 and above, meta package for http3"
  :url "http://github.com/sunng87/ring-jetty9-adapter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :deploy-repositories {"releases" :clojars}
  :global-vars {*warn-on-reflection* true}
  :dependencies [[org.clojure/clojure "1.12.1"]
                 [org.eclipse.jetty.http3/jetty-http3-server ~jetty-version]
                 [org.eclipse.jetty.quic/jetty-quic-quiche-server ~jetty-version]])
