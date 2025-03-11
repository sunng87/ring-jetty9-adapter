(ns ring.adapter.jetty9.http3
  (:import[org.eclipse.jetty.http3.api Session$Server$Listener]
           [org.eclipse.jetty.http3 HTTP3Configuration]
           [org.eclipse.jetty.http3.server RawHTTP3ServerConnectionFactory]
           [org.eclipse.jetty.quic.server ServerQuicConfiguration QuicServerConnector]
           [org.eclipse.jetty.http3.api Session$Server$Listener]
           [java.nio.file Path]))

(defn- default-sesison-listener []
  (reify Session$Server$Listener))

(defn- http3-server-connection-factory
  "Configure http3 specific options on HTTP3ServerConnectionFactory"
  [quic-config http3-options]
  (let [{:keys [input-buffer-size max-blocked-streams
                max-request-headers-size max-response-headers-size
                output-buffer-size stream-idle-timeout
                use-input-direct-byte-buffers use-output-direct-byte-buffers]}
        http3-options

        http3-config-factory
        (RawHTTP3ServerConnectionFactory. quic-config (default-sesison-listener))

        ^HTTP3Configuration
        http3-configuration (.getHTTP3Configuration http3-config-factory)

        option-provided?
        #(contains? http3-options %)]
    (cond-> http3-configuration
      (option-provided? :input-buffer-size)
      (doto (.setInputBufferSize input-buffer-size))

      (option-provided? :max-blocked-streams)
      (doto (.setMaxBlockedStreams max-blocked-streams))

      (option-provided? :max-request-headers-size)
      (doto (.setMaxRequestHeadersSize max-request-headers-size))

      (option-provided? :max-response-headers-size)
      (doto (.setMaxResponseHeadersSize max-response-headers-size))

      (option-provided? :output-buffer-size)
      (doto (.setOutputBufferSize output-buffer-size))

      (option-provided? :stream-idle-timeout)
      (doto (.setStreamIdleTimeout stream-idle-timeout))

      (option-provided? :use-input-direct-byte-buffers)
      (doto (.setUseInputDirectByteBuffers use-input-direct-byte-buffers))

      (option-provided? :use-output-direct-byte-buffers)
      (doto (.setUseOutputDirectByteBuffers use-output-direct-byte-buffers)))

    http3-config-factory))

(defn- server-quic-configuration
  "Configure quic specific options on HTTP3ServerConnector"
  [ssl-factory pem-work-directory quic-options]
  (let [{:keys [bidirectional-stream-recv-window disable-active-migration
                max-bidirectional-remote-streams max-unidirectional-remote-streams
                protocols session-recv-window
                unidirectional-stream-recv-window]}
        quic-options

        ^ServerQuicConfiguration quic-config
        (ServerQuicConfiguration. ssl-factory (Path/of pem-work-directory (into-array String [])))

        option-provided?
        #(contains? quic-options %)]
    (cond-> quic-config
      (option-provided? :bidirectional-stream-recv-window)
      (doto (.setBidirectionalStreamRecvWindow bidirectional-stream-recv-window))

      (option-provided? :disable-active-migration)
      (doto (.setDisableActiveMigration disable-active-migration))

      (option-provided? :max-bidirectional-remote-streams)
      (doto (.setMaxBidirectionalRemoteStreams max-bidirectional-remote-streams))

      (option-provided? :max-unidirectional-remote-streams)
      (doto (.setMaxUnidirectionalRemoteStreams max-unidirectional-remote-streams))

      (option-provided? :protocols)
      (doto (.setProtocols protocols))

      (option-provided? :session-recv-window)
      (doto (.setSessionRecvWindow session-recv-window))

      (option-provided? :unidirectional-stream-recv-window)
      (doto (.setUnidirectionalStreamRecvWindow unidirectional-stream-recv-window)))
    quic-config))

(defn quic-server-connector [server http3-options ssl-context-factory pem-work-directory port host]
  (let [quic-config (server-quic-configuration ssl-context-factory pem-work-directory http3-options)
        connection-factory (http3-server-connection-factory quic-config http3-options)
        connector (QuicServerConnector. server ^ServerQuicConfiguration quic-config
                                        (into-array RawHTTP3ServerConnectionFactory [connection-factory]))]
    (doto connector
      (.setPort port)
      (.setHost host))))
