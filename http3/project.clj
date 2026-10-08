(def jetty-version "12.1.13")

(defproject info.sunng/ring-jetty9-adapter-http3 "0.7.10"
  :description "Ring adapter for jetty 9 and above, meta package for http3"
  :url "http://github.com/sunng87/ring-jetty9-adapter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :deploy-repositories {"releases" :clojars}
  :global-vars {*warn-on-reflection* true}
  :dependencies [[org.clojure/clojure "1.12.6"]
                 [org.eclipse.jetty.http3/jetty-http3-server ~jetty-version]
                 [org.eclipse.jetty.quic/jetty-quic-quiche-server ~jetty-version]]
  :profiles {:dev {:dependencies [[clj-http "3.13.1"]
                                  [less-awful-ssl "1.0.8"]
                                  [org.eclipse.jetty/jetty-slf4j-impl ~jetty-version]
                                  [org.eclipse.jetty.quic/jetty-quic-quiche-foreign ~jetty-version]
                                  #_[stylefruits/gniazdo "1.1.4"]]
                   :resource-paths ["dev-resources"]})
