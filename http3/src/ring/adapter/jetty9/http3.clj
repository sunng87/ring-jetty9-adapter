(ns ring.adapter.jetty9.http3
  (:import [org.eclipse.jetty.http3.api Session$Server$Listener]
           [org.eclipse.jetty.http3 HTTP3Configuration]
           [org.eclipse.jetty.http3.server RawHTTP3ServerConnectionFactory]
           [org.eclipse.jetty.quic.quiche.server QuicheServerQuicConfiguration QuicheServerConnector]
           [org.eclipse.jetty.http3.api Session$Server$Listener]
           [java.nio.file Path]))

(defmacro cond->-config-options [configuration options config-items]
  `(cond-> ~configuration
     ~@(mapcat (fn [item]
                 (let [camel-case-item (clojure.string/replace (name item) #"-." #(clojure.string/upper-case (subs % 1)))
                       pascal-case-item (str (clojure.string/upper-case (subs camel-case-item 0 1)) (subs camel-case-item 1))]
                   [`(contains? ~options ~(keyword item))
                    `(doto (. ~(symbol (str "set" pascal-case-item)) (~(keyword item) ~options)))]))
               config-items)))

(defn- default-sesison-listener []
  (reify Session$Server$Listener))

(defn- http3-server-connection-factory
  "Configure http3 specific options on HTTP3ServerConnectionFactory"
  []
  ;;TODO: http config
  (let [http3-connection-factory
        (RawHTTP3ServerConnectionFactory. (default-sesison-listener))]

    http3-connection-factory))

(defn- server-quic-configuration
  "Configure quic specific options on HTTP3ServerConnector"
  [pem-work-directory quic-options]
  (let [^QuicheServerQuicConfiguration quic-config
        (QuicheServerQuicConfiguration. (Path/of pem-work-directory (into-array String [])))]

    (cond->-config-options quic-config quic-options
                           [session-max-data local-bidirectional-stream-max-data
                            remote-bidirectional-stream-max-data unidirectional-stream-max-data

                            bidirectional-max-streams unidirectional-max-streams

                            input-buffer-size output-buffer-size
                            use-input-direct-byte-buffers use-output-direct-byte-buffers

                            stream-idle-timeout min-input-buffer-space])

    quic-config))

(defn quic-server-connector [server http3-options ssl-context-factory pem-work-directory port host]
  (let [quic-config (server-quic-configuration pem-work-directory http3-options)
        connection-factory (http3-server-connection-factory)
        connector (QuicheServerConnector. server ssl-context-factory ^QuicheServerQuicConfiguration quic-config
                                          (into-array RawHTTP3ServerConnectionFactory [connection-factory]))]
    (doto connector
      (.setPort port)
      (.setHost host))))
