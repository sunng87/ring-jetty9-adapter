(ns ring.adapter.jetty9
  "Adapter for the Jetty 10 server, with websocket support.
  Derived from ring.adapter.jetty"
  (:import [org.eclipse.jetty.server
            Server Request ServerConnector Connector Handler
            HttpConfiguration HttpConnectionFactory
            ConnectionFactory SecureRequestCustomizer
            ProxyConnectionFactory]
           [org.eclipse.jetty.server.handler ContextHandler BufferedResponseHandler]
           [org.eclipse.jetty.util VirtualThreads]
           [org.eclipse.jetty.util.component AbstractLifeCycle]
           [org.eclipse.jetty.util.resource URLResourceFactory]
           [org.eclipse.jetty.util.thread
            QueuedThreadPool ScheduledExecutorScheduler ThreadPool]
           [org.eclipse.jetty.util.ssl KeyStoreScanner SslContextFactory SslContextFactory$Server]
           [org.eclipse.jetty.http2 HTTP2Cipher FlowControlStrategy$Factory]
           [org.eclipse.jetty.http2.server
            HTTP2CServerConnectionFactory HTTP2ServerConnectionFactory AbstractHTTP2ServerConnectionFactory]
           [org.eclipse.jetty.http2 WindowRateControl$Factory]
           [org.eclipse.jetty.alpn.server ALPNServerConnectionFactory]
           [java.security KeyStore]
           [ring.adapter.jetty9.handlers SyncProxyHandler AsyncProxyHandler])
  (:require
   [clojure.string :as string]
   [ring.adapter.jetty9.common :as common]
   [ring.adapter.jetty9.websocket :as ws]))

(def ws-upgrade-request? ws/ws-upgrade-request?)

(defn ^:internal proxy-handler
  "Returns a Jetty Handler implementation for the given Ring handler."
  [handler options]
  (SyncProxyHandler. handler options))

(defn ^:internal proxy-async-handler
  "Returns a Jetty Handler implementation for the given Ring **async** handler."
  [handler options]
  (AsyncProxyHandler. handler options))

(defn- http-config
  "Creates jetty http configurator"
  [{:as _
    :keys [ssl-port secure-scheme output-buffer-size output-aggregation-size
           request-header-size response-header-size send-server-version? send-date-header?
           header-cache-size sni-required? sni-host-check?]
    :or {ssl-port 443
         secure-scheme "https"
         output-buffer-size 32768
         output-aggregation-size 8192
         request-header-size 8192
         response-header-size 8192
         send-server-version? true
         send-date-header? false
         header-cache-size 1024
         sni-required? false
         sni-host-check? true}}]
  (let [secure-customizer (doto (SecureRequestCustomizer.)
                            (.setSniRequired sni-required?)
                            (.setSniHostCheck sni-host-check?))]
    (doto (HttpConfiguration.)
      (.setSecureScheme secure-scheme)
      (.setSecurePort ssl-port)
      (.setOutputBufferSize output-buffer-size)
      (.setOutputAggregationSize output-aggregation-size)
      (.setRequestHeaderSize request-header-size)
      (.setResponseHeaderSize response-header-size)
      (.setSendServerVersion send-server-version?)
      (.setSendDateHeader send-date-header?)
      (.setHeaderCacheSize header-cache-size)
      ;; duplicated with http configuration :max-idle-time
      #_(.setIdleTimeout idle-timeout-ms)
      (.addCustomizer secure-customizer))))

(defn- ^SslContextFactory$Server ssl-context-factory
  [{:as options
    :keys [keystore keystore-type key-password client-auth key-manager-password
           truststore trust-password truststore-type ssl-protocols ssl-provider
           exclude-ciphers replace-exclude-ciphers? exclude-protocols replace-exclude-protocols?
           ssl-context]}]
  (let [context-server (SslContextFactory$Server.)]
    (.setCipherComparator context-server HTTP2Cipher/COMPARATOR)
    (.setProvider context-server ssl-provider)
    ;; classpath support
    (if (string? keystore)
      (if (string/starts-with? keystore "classpath:")
        (.setKeyStoreResource context-server (.newSystemResource (URLResourceFactory.) (subs keystore 10)))
        (.setKeyStorePath context-server keystore))
      (.setKeyStore context-server ^KeyStore keystore))
    (when (string? keystore-type)
      (.setKeyStoreType context-server keystore-type))
    (.setKeyStorePassword context-server key-password)
    (when key-manager-password
      (.setKeyManagerPassword context-server key-manager-password))
    (cond
      (string? truststore)
      (.setTrustStorePath context-server truststore)
      (instance? KeyStore truststore)
      (.setTrustStore context-server ^KeyStore truststore))
    (when trust-password
      (.setTrustStorePassword context-server trust-password))
    (when truststore-type
      (.setTrustStoreType context-server truststore-type))
    (when ssl-context
      (.setSslContext context-server ssl-context))
    (case client-auth
      :need (.setNeedClientAuth context-server true)
      :want (.setWantClientAuth context-server true)
      nil)
    (when-let [exclude-ciphers exclude-ciphers]
      (let [ciphers (into-array String exclude-ciphers)]
        (if replace-exclude-ciphers?
          (.setExcludeCipherSuites context-server ciphers)
          (.addExcludeCipherSuites context-server ciphers))))
    (when ssl-protocols
      (.setIncludeProtocols context-server (into-array String ssl-protocols)))
    (when exclude-protocols
      (let [protocols (into-array String exclude-protocols)]
        (if replace-exclude-protocols?
          (.setExcludeProtocols context-server protocols)
          (.addExcludeProtocols context-server protocols))))
    context-server))

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

(defn- https-connector [server http-configuration ssl-context-factory h2? h2-options port host max-idle-time]
  (let [secure-connection-factory (concat (when h2? [(ALPNServerConnectionFactory. "h2,http/1.1")
                                                     (-> (HTTP2ServerConnectionFactory. http-configuration)
                                                         (http2-server-connection-factory h2-options))])
                                          [(HttpConnectionFactory. http-configuration)])]
    (doto (ServerConnector.
           ^Server server
           ^SslContextFactory$Server ssl-context-factory
           ^"[Lorg.eclipse.jetty.server.ConnectionFactory;" (into-array ConnectionFactory secure-connection-factory))
      (.setPort port)
      (.setHost host)
      (.setIdleTimeout max-idle-time))))

(defn- http-connector [server http-configuration h2c? h2-options port host max-idle-time proxy?]
  (let [plain-connection-factories (cond-> [(HttpConnectionFactory. http-configuration)]
                                     h2c? (concat [(-> (HTTP2CServerConnectionFactory. http-configuration)
                                                       (http2-server-connection-factory h2-options))])
                                     proxy? (concat [(ProxyConnectionFactory.)]))]
    (doto (ServerConnector.
           ^Server server
           ^"[Lorg.eclipse.jetty.server.ConnectionFactory;"
           (into-array ConnectionFactory plain-connection-factories))
      (.setPort port)
      (.setHost host)
      (.setIdleTimeout max-idle-time))))

(defn- http3-connector [& args]
  ;; load http3 module dynamically
  (let [http3-connector* @(requiring-resolve 'ring.adapter.jetty9.http3/http3-connector)]
    (apply http3-connector* args)))

(defn- create-server
  "Construct a Jetty Server instance."
  [{:as options
    :keys [port max-threads min-threads threadpool-idle-timeout virtual-threads? job-queue
           daemon? max-idle-time host ssl? ssl-port h2? h2c? h2-options http? proxy?
           thread-pool http3? http3-options ssl-hot-reload? http3-pem-work-directory]
    :or {port 80
         max-threads 50
         min-threads 8
         threadpool-idle-timeout 60000
         virtual-threads? false
         job-queue nil
         daemon? false
         max-idle-time 200000
         ssl? false
         http? true
         proxy? false}}]
  {:pre [(or http? ssl? ssl-port)]}
  (let [^ThreadPool pool (or thread-pool
                             (let [thread-pool (QueuedThreadPool. (int max-threads)
                                                                  (int min-threads)
                                                                  (int threadpool-idle-timeout)
                                                                  job-queue)]
                               (when virtual-threads?
                                 (.setVirtualThreadsExecutor thread-pool
                                                             (VirtualThreads/getDefaultVirtualThreadsExecutor)))
                               (doto thread-pool
                                 (.setDaemon daemon?))))
        http-configuration (http-config options)
        ssl? (or ssl? ssl-port)
        ssl-port (or ssl-port (when ssl? 443))
        ssl-factory (delay (ssl-context-factory options)) ;; lazy load this (if needed)
        server (doto (Server. pool)
                 (.addBean (ScheduledExecutorScheduler.))
                 ;; support custom lifecycle tied to the server's lifecycle
                 (.addBean (proxy [AbstractLifeCycle] []
                             (doStart [] (when-some [f (:lifecycle-start options)] (f)))
                             (doStop  [] (when-some [f (:lifecycle-end options)]   (f)))))
                 (.setStopAtShutdown true))
        connectors (cond-> []
                     ssl?  (conj (https-connector server http-configuration @ssl-factory
                                                  h2? h2-options ssl-port host max-idle-time))
                     http? (conj (http-connector server http-configuration h2c? h2-options port host max-idle-time proxy?))
                     http3? (conj (http3-connector server http-configuration
                                                   (assoc http3-options :pem-work-directory http3-pem-work-directory)
                                                   @ssl-factory ssl-port host)))]
    (when (and ssl?
               (not (false? ssl-hot-reload?))
               (some? (.getKeyStorePath ^SslContextFactory @ssl-factory)))
      (.addBean server (doto (KeyStoreScanner. @ssl-factory)
                         (.setScanInterval 3600)))) ;; seconds - i.e. 1 hour
    (doto server
      (.setConnectors (into-array Connector connectors)))))

(defn run-jetty
  "
  Start a Jetty webserver to serve the given handler according to the
  supplied options:

  :configurator - a function called with the Jetty Server instance (allows for configuration beyond the supported options listed below)
  :http? - allow connections over HTTP
  :port - the port to listen on (defaults to 80)
  :host - the hostname to listen on
  :async? - using Ring 1.6 async handler?
  :join? - blocks the thread until server ends (defaults to true)
  :daemon? - use daemon threads (defaults to false)
  :ssl? - allow connections over HTTPS
  :ssl-port - the SSL port to listen on (defaults to 443, implies :ssl?)
  :ssl-hot-reload? - watch the keystore file for changes (defaults to true when ssl?)
  :lifecycle-start - a no-arg fn to call when the server starts (per the server's `LifeCycle.doStart`)
  :lifecycle-end - a no-arg fn to call when the server stops (per the server's `LifeCycle.doStop`)
  :ssl-context - an optional SSLContext to use for SSL connections
  :keystore - the keystore to use for SSL connections
  :keystore-type - the format of keystore
  :key-password - the password to the keystore
  :key-manager-password - the password for key manager
  :truststore - a truststore to use for SSL connections
  :truststore-type - the format of trust store
  :trust-password - the password to the truststore
  :ssl-protocols - the ssl protocols to use, default to [\"TLSv1.3\" \"TLSv1.2\"]
  :ssl-provider - the ssl provider, default to \"Conscrypt\"
  :exclude-ciphers      - when :ssl? is true, additionally exclude these
                          cipher suites
  :exclude-protocols    - when :ssl? is true, additionally exclude these
                          protocols
  :replace-exclude-ciphers?   - when true, :exclude-ciphers will replace rather
                                than add to the cipher exclusion list (defaults
                                to false)
  :replace-exclude-protocols? - when true, :exclude-protocols will replace
                                rather than add to the protocols exclusion list
                                (defaults to false)
  :thread-pool - the thread pool for Jetty workload, accepts an instance of `org.eclipse.jetty.util.thread.ThreadPool`
  :max-threads - the maximum number of threads to use (default 50), ignored if `:thread-pool` provided
  :min-threads - the minimum number of threads to use (default 8), ignored if `:thread-pool` provided
  :threadpool-idle-timeout - the maximum idle time in milliseconds for a thread (default 60000), ignored if `:thread-pool` provided
  :job-queue - the job queue to be used by the Jetty threadpool (default is unbounded), ignored if `:thread-pool` provided
  :virtual-threads? - to enable virtual threads for thread pool, ignored if `:thread-pool` provided
  :max-idle-time  - the maximum idle time in milliseconds for a connection (default 200000)
  :output-buffer-size - size of http response buffer, default to 32768
  :output-aggregation-size - size of http aggregation size, defualt to 8192
  :ws-configurator - a function called with the websocket container instance (allows for configuration beyond the supported options listed below)
  :ws-max-idle-time - the maximum idle time in milliseconds for a websocket connection (default 30000, inherited from jetty defaults)
  :ws-max-frame-size - the maximum message size in bytes for a websocket connection (default 65536, inherited from jetty defaults)
  :ws-max-binary-message-size - the maximum binary message size in bytes for a websocket connection (default 65536, inherited from jetty defaults)
  :ws-max-text-message-size  - the maximum text message size in bytes for a websocket connection (default 65536, inherited from jetty defaults)
  :client-auth - SSL client certificate authenticate, may be set to :need, :want or :none (defaults to :none)
  :h2? - enable http2 protocol on secure socket port
  :h2c? - enable http2 clear text on plain socket port
  :h2-options - map with options specific for http2 (all setters from https://www.eclipse.org/jetty/javadoc/jetty-11/org/eclipse/jetty/http2/server/AbstractHTTP2ServerConnectionFactory.html,
                kebab cased without \"set\", e.g. setMaxConcurrentStreams -> max-concurrent-streams)
  :proxy? - enable the proxy protocol on plain socket port (see http://www.eclipse.org/jetty/documentation/9.4.x/configuring-connectors.html#_proxy_protocol)
  :wrap-jetty-handler - a wrapper fn that wraps default jetty handler into another, default to `identity`, not that it's not a ring middleware
  :sni-required? - require sni for secure connection, default to false
  :sni-host-check? - enable host check for secure connection, default to true
  :http3? - enable http3 protocol, make sure you have `info.sunng/ring-jetty9-adapter-http3` package on classpath
  :http3-pem-work-directory - required when http3 enabled, specify a directory as http3 pem work dir
  :http3-options - map with options specific for http3
                  (all setters from https://www.eclipse.org/jetty/javadoc/jetty-11/org/eclipse/jetty/http3/HTTP3Configuration.html
                   and https://www.eclipse.org/jetty/javadoc/jetty-11/org/eclipse/jetty/quic/common/QuicConfiguration.html,
                   kebab cased without \"set\", e.g. setStreamIdleTimeout -> stream-idle-timeout)"
  [handler {:as options
            :keys [configurator join? async?
                   allow-null-path-info wrap-jetty-handler]
            :or {allow-null-path-info false
                 join? true
                 wrap-jetty-handler identity}}]
  (let [^Server s (create-server options)
        context-handler (ContextHandler. "/")
        ring-app-handler (if async?
                           (proxy-async-handler handler options)
                           (proxy-handler handler options))]
    (.setHandler context-handler ^Handler ring-app-handler)
    (.setHandler s ^Handler context-handler)
    (ws/ensure-container s context-handler)
    (when-let [c configurator]
      (c s))
    (.start s)
    (when join?
      (.join s))
    s))

(defn stop-server
  [^Server s]
  (.stop s))
