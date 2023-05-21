(ns ring.adapter.jetty9.http3
  (:import [org.eclipse.jetty.server
            HttpConfiguration SecureRequestCustomizer]
           [org.eclipse.jetty.http3 HTTP3Configuration]
           [org.eclipse.jetty.http3.server
            HTTP3ServerConnectionFactory HTTP3ServerConnector
            AbstractHTTP3ServerConnectionFactory]
           [org.eclipse.jetty.quic.common QuicConfiguration]
           [org.eclipse.jetty.http3.api Session$Server$Listener]))

(defn- http3-server-connection-factory
  "Configure http3 specific options on HTTP3ServerConnectionFactory created from HttpConfiguration"
  ([^AbstractHTTP3ServerConnectionFactory factory-from-http-config]
   (http3-server-connection-factory factory-from-http-config nil))
  ([^AbstractHTTP3ServerConnectionFactory factory-from-http-config http3-options]
   (let [{:keys [input-buffer-size max-blocked-streams
                 max-request-headers-size max-response-headers-size
                 output-buffer-size stream-idle-timeout
                 use-input-direct-byte-buffers use-output-direct-byte-buffers]}
         http3-options

         ^HTTP3Configuration http3-config
         (.getHTTP3Configuration factory-from-http-config)

         option-provided?
         #(contains? http3-options %)]
     (cond-> http3-config
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
     factory-from-http-config)))

(defn- http3-server-connector
  "Configure quic specific options on HTTP3ServerConnector"
  ([^HTTP3ServerConnector http3-connector-default]
   (http3-server-connector http3-connector-default nil))
  ([^HTTP3ServerConnector http3-connector-default http3-options]
   (let [{:keys [bidirectional-stream-recv-window disable-active-migration
                 max-bidirectional-remote-streams max-unidirectional-remote-streams
                 protocols session-recv-window
                 unidirectional-stream-recv-window verify-peer-certificates]}
         http3-options

         ^QuicConfiguration quic-config
         (.getQuicConfiguration http3-connector-default)

         option-provided?
         #(contains? http3-options %)]
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
       (doto (.setUnidirectionalStreamRecvWindow unidirectional-stream-recv-window))

       (option-provided? :verify-peer-certificates)
       (doto (.setVerifyPeerCertificates verify-peer-certificates)))
     http3-connector-default)))

(defn http3-connector [server http-configuration http3-options ssl-context-factory port host]
  (let [connection-factory (-> (HTTP3ServerConnectionFactory. http-configuration)
                               (http3-server-connection-factory http3-options))
        connector (-> (HTTP3ServerConnector. server ssl-context-factory
                                             (into-array HTTP3ServerConnectionFactory [connection-factory]))
                      (http3-server-connector http3-options))]
    (doto connector
      (.setPort port)
      (.setHost host))))
