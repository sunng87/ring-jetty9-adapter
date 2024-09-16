(ns ring.adapter.jetty9.http2
  (:import [org.eclipse.jetty.http2 HTTP2Cipher FlowControlStrategy$Factory]
           [org.eclipse.jetty.http2.server
            HTTP2CServerConnectionFactory HTTP2ServerConnectionFactory AbstractHTTP2ServerConnectionFactory]
           [org.eclipse.jetty.http2 WindowRateControl$Factory]
           [org.eclipse.jetty.alpn.server ALPNServerConnectionFactory]))

(defn- http2-server-connection-factory
  "Set AbstractHTTP2ServerConnectionFactory specific options for connection factory presumably created from HttpConfiguration"
  ([^AbstractHTTP2ServerConnectionFactory factory-from-http-config]
   (http2-server-connection-factory factory-from-http-config nil))
  ([^AbstractHTTP2ServerConnectionFactory factory-from-http-config h2-options]
   (let [{:keys [connect-protocol-enabled
                 ^FlowControlStrategy$Factory flow-control-strategy-factory
                 initial-session-recv-window initial-stream-recv-window max-concurrent-streams max-dynamic-table-size
                 max-frame-length max-header-block-fragment max-setting-keys
                 ^WindowRateControl$Factory rate-control-factory
                 stream-idle-timeout use-input-direct-byte-buffers use-output-direct-byte-buffers]}
         h2-options

         option-provided?
         #(contains? h2-options %)]
     (cond-> factory-from-http-config
       (option-provided? :connect-protocol-enabled)
       (doto (.setConnectProtocolEnabled connect-protocol-enabled))

       (option-provided? :flow-control-strategy-factory)
       (doto (.setFlowControlStrategyFactory flow-control-strategy-factory))

       (option-provided? :initial-session-recv-window)
       (doto (.setInitialSessionRecvWindow initial-session-recv-window))

       (option-provided? :initial-stream-recv-window)
       (doto (.setInitialStreamRecvWindow initial-stream-recv-window))

       (option-provided? :max-concurrent-streams)
       (doto (.setMaxConcurrentStreams max-concurrent-streams))

       (option-provided? :max-dynamic-table-size)
       (doto (.setMaxDecoderTableCapacity max-dynamic-table-size))

       (option-provided? :max-frame-length)
       (doto (.setMaxFrameSize max-frame-length))

       (option-provided? :max-header-block-fragment)
       (doto (.setMaxHeaderBlockFragment max-header-block-fragment))

       (option-provided? :max-setting-keys)
       (doto (.setMaxSettingsKeys max-setting-keys))

       (option-provided? :rate-control-factory)
       (doto (.setRateControlFactory rate-control-factory))

       (option-provided? :stream-idle-timeout)
       (doto (.setStreamIdleTimeout stream-idle-timeout))

       (option-provided? :use-input-direct-byte-buffers)
       (doto (.setUseInputDirectByteBuffers use-input-direct-byte-buffers))

       (option-provided? :use-output-direct-byte-buffers)
       (doto (.setUseOutputDirectByteBuffers use-output-direct-byte-buffers))))))

(defn http2-alpn-connection-factory [http-configuration h2-options]
  [(ALPNServerConnectionFactory. "h2,http/1.1")
   (-> (HTTP2ServerConnectionFactory. http-configuration)
       (http2-server-connection-factory h2-options))])

(defn http2-clear-connection-factory [http-configuration h2-options]
  [(-> (HTTP2CServerConnectionFactory. http-configuration)
       (http2-server-connection-factory h2-options))])

(def http2-cipher-comparator
  HTTP2Cipher/COMPARATOR)
