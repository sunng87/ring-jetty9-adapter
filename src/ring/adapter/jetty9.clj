(ns ring.adapter.jetty9
  "Adapter for the Jetty 9 server, with websocket support.
  Derived from ring.adapter.jetty"
  (:import [org.eclipse.jetty.server
            Handler Server Request ServerConnector
            HttpConfiguration HttpConnectionFactory
            SslConnectionFactory ConnectionFactory
            ProxyConnectionFactory]
           [org.eclipse.jetty.server.handler
            HandlerCollection AbstractHandler ContextHandler HandlerList]
           [org.eclipse.jetty.util.thread
            QueuedThreadPool ScheduledExecutorScheduler ThreadPool]
           [org.eclipse.jetty.util.ssl SslContextFactory SslContextFactory$Server]
           [javax.servlet.http HttpServletRequest HttpServletResponse]
           [javax.servlet AsyncContext]
           [org.eclipse.jetty.http2
            HTTP2Cipher]
           [org.eclipse.jetty.http2.server
            HTTP2CServerConnectionFactory HTTP2ServerConnectionFactory]
           [org.eclipse.jetty.alpn.server ALPNServerConnectionFactory]
           [java.security KeyStore])
  (:require [ring.util.servlet :as servlet]
            [ring.adapter.jetty9.common :refer :all]
            [ring.adapter.jetty9.websocket :refer [proxy-ws-handler] :as ws]))

(def send! ws/send!)
(def ping! ws/ping!)
(def close! ws/close!)
(def remote-addr ws/remote-addr)
(def idle-timeout! ws/idle-timeout!)
(def connected? ws/connected?)
(def req-of ws/req-of)

(extend-protocol RequestMapDecoder
  HttpServletRequest
  (build-request-map [request]
    (servlet/build-request-map request)))

(defn normalize-response
  "Normalize response for ring spec"
  [response]
  (cond
    (string? response) {:body response}
    :else response))


(defn ^:internal proxy-handler
  "Returns an Jetty Handler implementation for the given Ring handler."
  [handler]
  (proxy [AbstractHandler] []
    (handle [_ ^Request base-request ^HttpServletRequest request ^HttpServletResponse response]
      (try
        (let [request-map (build-request-map request)
              response-map (-> (handler request-map)
                               normalize-response)]
          (when response-map
            (servlet/update-servlet-response response response-map)))
        (catch Throwable e
          (.sendError response 500 (.getMessage e)))
        (finally
          (.setHandled base-request true))))))

(defn ^:internal proxy-async-handler
  "Returns an Jetty Handler implementation for the given Ring **async** handler."
  [handler]
  (proxy [AbstractHandler] []
    (handle [_ ^Request base-request ^HttpServletRequest request ^HttpServletResponse response]
      (try
        (let [^AsyncContext context (.startAsync request)]
          (handler
           (servlet/build-request-map request)
           (fn [response-map]
             (let [response-map (normalize-response response-map)]
               (servlet/update-servlet-response response context response-map)))
           (fn [^Throwable exception]
             (.sendError response 500 (.getMessage exception))
             (.complete context))))
        (finally
          (.setHandled base-request true))))))

(defn- http-config
  [{:as options
    :keys [ssl-port secure-scheme output-buffer-size request-header-size
           response-header-size send-server-version? send-date-header?
           header-cache-size]
    :or {ssl-port 443
         secure-scheme "https"
         output-buffer-size 32768
         request-header-size 8192
         response-header-size 8192
         send-server-version? true
         send-date-header? false
         header-cache-size 512}}]
  "Creates jetty http configurator"
  (doto (HttpConfiguration.)
    (.setSecureScheme secure-scheme)
    (.setSecurePort ssl-port)
    (.setOutputBufferSize output-buffer-size)
    (.setRequestHeaderSize request-header-size)
    (.setResponseHeaderSize response-header-size)
    (.setSendServerVersion send-server-version?)
    (.setSendDateHeader send-date-header?)
    (.setHeaderCacheSize header-cache-size)))

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
           exclude-ciphers replace-exclude-ciphers? exclude-protocols replace-exclude-protocols?]
    :or {ssl-protocols ["TLSv1.3" "TLSv1.2"]}}]
  (let [context-server (SslContextFactory$Server.)]
    (.setCipherComparator context-server HTTP2Cipher/COMPARATOR)
    (let [ssl-provider (or ssl-provider (detect-ssl-provider))]
      (.setProvider context-server ssl-provider))
    (if (string? keystore)
      (.setKeyStorePath context-server keystore)
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
    (case client-auth
      :need (.setNeedClientAuth context-server true)
      :want (.setWantClientAuth context-server true)
      nil)
    (when-let [exclude-ciphers exclude-ciphers]
      (let [ciphers (into-array String exclude-ciphers)]
        (if replace-exclude-ciphers?
          (.setExcludeCipherSuites context-server ciphers)
          (.addExcludeCipherSuites context-server ciphers))))
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
            ^SslContextFactory ssl-context-factory
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

(defn- create-server
  "Construct a Jetty Server instance."
  [{:as options
    :keys [port max-threads min-threads threadpool-idle-timeout job-queue
           daemon? max-idle-time host ssl? ssl-port h2? h2c? http? proxy?
           thread-pool]
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
                 (.addBean (ScheduledExecutorScheduler.)))
        http-configuration (http-config options)
        ssl? (or ssl? ssl-port)
        connectors (cond-> []
                     ssl?  (conj (https-connector server http-configuration (ssl-context-factory options)
                                                  h2? ssl-port host max-idle-time))
                     http? (conj (http-connector server http-configuration h2c? port host max-idle-time proxy?)))]
    (.setConnectors server (into-array connectors))
    server))

(defn ^Server run-jetty
  "
  Start a Jetty webserver to serve the given handler according to the
  supplied options:

  :http? - allow connections over HTTP
  :port - the port to listen on (defaults to 80)
  :host - the hostname to listen on
  :async? - using Ring 1.6 async handler?
  :join? - blocks the thread until server ends (defaults to true)
  :daemon? - use daemon threads (defaults to false)
  :ssl? - allow connections over HTTPS
  :ssl-port - the SSL port to listen on (defaults to 443, implies :ssl?)
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
  :thread-pool - the thread pool for Jetty workload
  :max-threads - the maximum number of threads to use (default 50), ignored if `:thread-pool` provided
  :min-threads - the minimum number of threads to use (default 8), ignored if `:thread-pool` provided
  :threadpool-idle-timeout - the maximum idle time in milliseconds for a thread (default 60000), ignored if `:thread-pool` provided
  :job-queue - the job queue to be used by the Jetty threadpool (default is unbounded), ignored if `:thread-pool` provided
  :max-idle-time  - the maximum idle time in milliseconds for a connection (default 200000)
  :ws-max-idle-time  - the maximum idle time in milliseconds for a websocket connection (default 500000)
  :ws-max-text-message-size  - the maximum text message size in bytes for a websocket connection (default 65536) 
  :client-auth - SSL client certificate authenticate, may be set to :need, :want or :none (defaults to :none)
  :websockets - a map from context path to a map of handler fns:
   {\"/context\" {:on-connect #(create-fn %)              ; ^Session ws-session
                :on-text   #(text-fn % %2 %3 %4)         ; ^Session ws-session message
                :on-bytes  #(binary-fn % %2 %3 %4 %5 %6) ; ^Session ws-session payload offset len
                :on-close  #(close-fn % %2 %3 %4)        ; ^Session ws-session statusCode reason
                :on-error  #(error-fn % %2 %3)}}         ; ^Session ws-session e
   or a custom creator function take upgrade request as parameter and returns a handler fns map (or error info)
  :h2? - enable http2 protocol on secure socket port
  :h2c? - enable http2 clear text on plain socket port
  :proxy? - enable the proxy protocol on plain socket port (see http://www.eclipse.org/jetty/documentation/9.4.x/configuring-connectors.html#_proxy_protocol)
  :wrap-jetty-handler - a wrapper fn that wraps default jetty handler into another, default to `identity`, not that it's not a ring middleware
  "
  [handler {:as options
            :keys [max-threads websockets configurator join? async?
                   allow-null-path-info wrap-jetty-handler]
            :or {allow-null-path-info false
                 join? true
                 wrap-jetty-handler identity}}]
  (let [^Server s (create-server options)
        ring-app-handler (wrap-jetty-handler
                          (if async? (proxy-async-handler handler) (proxy-handler handler)))
        ws-handlers (map (fn [[context-path handler]]
                           (doto (ContextHandler.)
                             (.setContextPath context-path)
                             (.setAllowNullPathInfo allow-null-path-info)
                             (.setHandler (proxy-ws-handler handler options))))
                         websockets)
        contexts (doto (HandlerList.)
                   (.setHandlers
                    (into-array Handler (reverse (conj ws-handlers ring-app-handler)))))]
    (.setHandler s contexts)
    (when-let [c configurator]
      (c s))
    (.start s)
    (when join?
      (.join s))
    s))

(defn stop-server [^Server s]
  (.stop s))
