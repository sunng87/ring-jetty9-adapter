(ns ring.adapter.jetty9
  "Adapter for the Jetty 10 server, with websocket support.
  Derived from ring.adapter.jetty"
  (:import [java.net URI]
           [java.util.function Consumer]
           [org.eclipse.jetty.server
            Server Request ServerConnector Connector
            HttpConfiguration HttpConnectionFactory
            ConnectionFactory SecureRequestCustomizer
            ProxyConnectionFactory]
           [org.eclipse.jetty.servlet ServletContextHandler ServletHandler]
           [org.eclipse.jetty.util.component AbstractLifeCycle]
           [org.eclipse.jetty.util.resource Resource]
           [org.eclipse.jetty.util.thread
            QueuedThreadPool ScheduledExecutorScheduler ThreadPool]
           [org.eclipse.jetty.util.ssl SslContextFactory SslContextFactory$Server]
           [org.eclipse.jetty.websocket.server.config JettyWebSocketServletContainerInitializer]
           [jakarta.servlet.http HttpServletRequest HttpServletResponse]
           [jakarta.servlet AsyncContext]
           [org.eclipse.jetty.http2 HTTP2Cipher]
           [org.eclipse.jetty.http2.server
            HTTP2CServerConnectionFactory HTTP2ServerConnectionFactory]
           [org.eclipse.jetty.alpn.server ALPNServerConnectionFactory]
           [java.security KeyStore]
           [ring.adapter.jetty9.handlers SyncProxyHandler AsyncProxyHandler])
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [ring.adapter.jetty9.common :refer [RequestMapDecoder build-request-map on-file-change! noop]]
    [ring.adapter.jetty9.servlet :as servlet]
    [ring.adapter.jetty9.websocket :as ws]))

(def send! ws/send!)
(def ping! ws/ping!)
(def close! ws/close!)
(def remote-addr ws/remote-addr)
(def idle-timeout! ws/idle-timeout!)
(def connected? ws/connected?)
(def req-of ws/req-of)
(def ws-upgrade-request? ws/ws-upgrade-request?)
(def ws-upgrade-response ws/ws-upgrade-response)

(extend-protocol RequestMapDecoder
  HttpServletRequest
  (build-request-map [request]
    (servlet/build-request-map request)))

(defn ^:internal wrap-proxy-handler
  "Wraps a Jetty handler in a ServletContextHandler.

   Websocket upgrades require a servlet context which makes it
   necessary to wrap the handler in a servlet context handler."
  [jetty-handler]
  (doto (ServletContextHandler.)
    ;; avoid warnings
    #_(.setContextPath "/*")
    (.setAllowNullPathInfo true)
    (JettyWebSocketServletContainerInitializer/configure nil)
    (.setServletHandler jetty-handler)))

(defn ^:internal proxy-handler
  "Returns a Jetty Handler implementation for the given Ring handler."
  [handler options]
  (wrap-proxy-handler
    (SyncProxyHandler. handler options)))

(defn ^:internal proxy-async-handler
  "Returns a Jetty Handler implementation for the given Ring **async** handler."
  [handler options]
  (wrap-proxy-handler
    (AsyncProxyHandler. handler options)))

(defn- http-config
  "Creates jetty http configurator"
  [{:as _
    :keys [ssl-port secure-scheme output-buffer-size request-header-size
           response-header-size send-server-version? send-date-header?
           header-cache-size sni-required? sni-host-check?]
    :or {ssl-port 443
         secure-scheme "https"
         output-buffer-size 32768
         request-header-size 8192
         response-header-size 8192
         send-server-version? true
         send-date-header? false
         header-cache-size 512
         sni-required? false
         sni-host-check? true}}]
  (let [secure-customizer (doto (SecureRequestCustomizer.)
                            (.setSniRequired sni-required?)
                            (.setSniHostCheck sni-host-check?))]
    (doto (HttpConfiguration.)
      (.setSecureScheme secure-scheme)
      (.setSecurePort ssl-port)
      (.setOutputBufferSize output-buffer-size)
      (.setRequestHeaderSize request-header-size)
      (.setResponseHeaderSize response-header-size)
      (.setSendServerVersion send-server-version?)
      (.setSendDateHeader send-date-header?)
      (.setHeaderCacheSize header-cache-size)
      (.addCustomizer secure-customizer))))

(defn- detect-ssl-provider []
  (try
    (Class/forName "org.conscrypt.Conscrypt")
    "Conscrypt"
    (catch Throwable _
      ;; fallback to default jdk provider
      nil)))

(defn- ^SslContextFactory$Server ssl-context-factory
  [{:as options
    :keys [keystore keystore-type key-password client-auth key-manager-password
           truststore trust-password truststore-type ssl-protocols ssl-provider
           exclude-ciphers replace-exclude-ciphers? exclude-protocols replace-exclude-protocols?
           ssl-context]}]
  (let [context-server (SslContextFactory$Server.)]
    (.setCipherComparator context-server HTTP2Cipher/COMPARATOR)
    (let [ssl-provider (or ssl-provider (detect-ssl-provider))]
      (.setProvider context-server ssl-provider))
    ;; classpath support
    (if (string? keystore)
      (if (string/starts-with? keystore "classpath:")
        (.setKeyStoreResource context-server (Resource/newSystemResource (subs keystore 10)))
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

(defn- https-connector [server http-configuration ssl-context-factory h2? port host max-idle-time]
  (let [secure-connection-factory (concat (when h2? [(ALPNServerConnectionFactory. "h2,http/1.1")
                                                     (HTTP2ServerConnectionFactory. http-configuration)])
                                          [(HttpConnectionFactory. http-configuration)])]
    (doto (ServerConnector.
           ^Server server
           ^SslContextFactory$Server ssl-context-factory
           ^"[Lorg.eclipse.jetty.server.ConnectionFactory;" (into-array ConnectionFactory secure-connection-factory))
      (.setPort port)
      (.setHost host)
      (.setIdleTimeout max-idle-time))))

(defn- http-connector [server http-configuration h2c? port host max-idle-time proxy?]
  (let [plain-connection-factories (cond-> [(HttpConnectionFactory. http-configuration)]
                                     h2c? (concat [(HTTP2CServerConnectionFactory. http-configuration)])
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
    :keys [port max-threads min-threads threadpool-idle-timeout job-queue
           daemon? max-idle-time host ssl? ssl-port h2? h2c? http? proxy?
           thread-pool http3? ssl-hot-reload? ssl-hot-reload-callback]
    :or {port 80
         max-threads 50
         min-threads 8
         threadpool-idle-timeout 60000
         job-queue nil
         daemon? false
         max-idle-time 200000
         ssl? false
         http? true
         proxy? false}}]
  {:pre [(or http? ssl? ssl-port)]}
  (let [pool (or thread-pool
                 (doto (QueuedThreadPool. (int max-threads)
                                          (int min-threads)
                                          (int threadpool-idle-timeout)
                                          job-queue)
                   (.setDaemon daemon?)))
        server (doto (Server. ^ThreadPool pool)
                 (.addBean (ScheduledExecutorScheduler.))
                 (.addBean (proxy [AbstractLifeCycle] []
                             (doStart [] (when-some [f (:lifecycle-start options)] (f)))
                             (doStop  [] (when-some [f (:lifecycle-end options)] (f)))))
                 (.setStopAtShutdown true))
        http-configuration (http-config options)
        ssl? (or ssl? ssl-port)
        ssl-port (or ssl-port (when ssl? 443))
        ssl-factory (delay (ssl-context-factory options)) ;; might end up not needing it
        connectors (cond-> []
                     ssl?  (conj (https-connector server http-configuration @ssl-factory
                                                  h2? ssl-port host max-idle-time))
                     http? (conj (http-connector server http-configuration h2c? port host max-idle-time proxy?))
                     http3? (conj (http3-connector server http-configuration @ssl-factory ssl-port host)))]
    [(doto server (.setConnectors (into-array Connector connectors)))
     ;; https://github.com/sunng87/ring-jetty9-adapter/issues/90
     (when (and ssl?                        ;; ssl is enabled and
                (or ssl-hot-reload-callback ;; we either have a callback
                    (not (false? ssl-hot-reload?)))) ;; or hot-reload is not explicitly disabled
       (let [callback (or ssl-hot-reload-callback noop) ;; this is optional so provide a default
             ^SslContextFactory factory @ssl-factory]
         (some-> (.getKeyStorePath factory)
                 URI.
                 io/file
                 (on-file-change!
                   (fn [_] ;; the file above
                     (->> (reify Consumer (accept [_ scf] (callback scf)))
                          (.reload factory)))))))]))

(defn run-jetty
  "
  Start a Jetty webserver to serve the given handler according to the
  supplied options:

  :http? - allow connections over HTTP
  :port - the port to listen on (defaults to 80)
  :host - the hostname to listen on
  :async? - using Ring 1.6 async handler?
  :async-timeout - the maximum time in milliseconds for an async request (default 30000)
  :join? - blocks the thread until server ends (defaults to true)
  :daemon? - use daemon threads (defaults to false)
  :ssl? - allow connections over HTTPS
  :ssl-port - the SSL port to listen on (defaults to 443, implies :ssl?)
  :ssl-hot-reload? - watch the keystore file for changes (defaults to true when ssl?)
  :ssl-hot-reload-callback - a function of 1-arg (the SslContextFactory) to call when the keystore is reloaded (e.g. logging) - implies :ssl-hot-reload?
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
  :max-idle-time  - the maximum idle time in milliseconds for a connection (default 200000)
  :ws-max-idle-time  - the maximum idle time in milliseconds for a websocket connection (default 500000)
  :ws-max-text-message-size  - the maximum text message size in bytes for a websocket connection (default 65536)
  :client-auth - SSL client certificate authenticate, may be set to :need, :want or :none (defaults to :none)
  :h2? - enable http2 protocol on secure socket port
  :h2c? - enable http2 clear text on plain socket port
  :proxy? - enable the proxy protocol on plain socket port (see http://www.eclipse.org/jetty/documentation/9.4.x/configuring-connectors.html#_proxy_protocol)
  :wrap-jetty-handler - a wrapper fn that wraps default jetty handler into another, default to `identity`, not that it's not a ring middleware
  :sni-required? - require sni for secure connection, default to false
  :sni-host-check? - enable host check for secure connection, default to true
  :http3? - enable http3 protocol, make sure you have `info.sunng/ring-jetty9-adapter-http3` package on classpath"
  [handler {:as options
            :keys [configurator join? async?
                   allow-null-path-info wrap-jetty-handler]
            :or {allow-null-path-info false
                 join? true
                 wrap-jetty-handler identity}}]
  (let [[^Server s keystore-watch] (create-server options)
        ring-app-handler (wrap-jetty-handler
                           (if async?
                             (proxy-async-handler handler options)
                             (proxy-handler handler options)))]
    (.setHandler s ring-app-handler)
    (when-let [c configurator]
      (c s))
    (.start s)
    (when join?
      (.join s))
    {:server s
     :stop-jetty (fn []
                   (some-> keystore-watch future-cancel)
                   (.stop s))}))

(defn stop-server
  [{:keys [stop-jetty]}]
  (stop-jetty))
