(ns ring.adapter.jetty9.http3
  (:import [org.eclipse.jetty.server
            HttpConfiguration SecureRequestCustomizer]
           [org.eclipse.jetty.http3.server
            HTTP3ServerConnectionFactory HTTP3ServerConnector]
           [org.eclipse.jetty.http3.api Session$Server$Listener]))

(defn http3-connector [server ssl-context-factory port host]
  (let [http-config (doto (HttpConfiguration.)
                      (.addCustomizer (SecureRequestCustomizer.)))
        connection-factory (HTTP3ServerConnectionFactory. http-config)
        connector (HTTP3ServerConnector. server ssl-context-factory
                                         (into-array HTTP3ServerConnectionFactory [connection-factory]))]
    (doto connector
      (.setPort port)
      (.setHost host))))
