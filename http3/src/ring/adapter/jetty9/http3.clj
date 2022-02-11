(ns ring.adapter.jetty9.http3
  (:import [org.eclipse.jetty.http3.server
            RawHTTP3ServerConnectionFactory HTTP3ServerConnector]
           [org.eclipse.jetty.http3.api Session$Server$Listener]))

(defn http3-connector [server ssl-context-factory port host]
  (let [listener (reify Session$Server$Listener)
        connection-factory (RawHTTP3ServerConnectionFactory. listener)
        connector (HTTP3ServerConnector. server ssl-context-factory
                                         (into-array RawHTTP3ServerConnectionFactory [connection-factory]))]
    (doto connector
      (.setPort port)
      (.setHost host))))
