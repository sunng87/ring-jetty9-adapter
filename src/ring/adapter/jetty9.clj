(ns ring.adapter.jetty9
  "Adapter for the Jetty 9 server, with websocket support.
  Derived from ring.adapter.jetty"
  (:import [org.eclipse.jetty.server
            Handler Server Request ServerConnector
            HttpConfiguration HttpConnectionFactory
            SslConnectionFactory ConnectionFactory]
           [org.eclipse.jetty.server.handler
            HandlerCollection AbstractHandler ContextHandler HandlerList]
           [org.eclipse.jetty.util.thread
            QueuedThreadPool ScheduledExecutorScheduler]
           [org.eclipse.jetty.util.ssl SslContextFactory]
           [javax.servlet.http HttpServletRequest HttpServletResponse]
           [org.eclipse.jetty.http2.server
            HTTP2CServerConnectionFactory HTTP2ServerConnectionFactory])
  (:require [ring.util.servlet :as servlet]
            [ring.adapter.jetty9.common :refer :all]
            [ring.adapter.jetty9.websocket :refer [proxy-ws-handler] :as ws]))

(def send! ws/send!)
(def close! ws/close!)
(def remote-addr ws/remote-addr)
(def idle-timeout! ws/idle-timeout!)
(def connected? ws/connected?)
(def req-of ws/req-of)

(extend-protocol RequestMapDecoder
  HttpServletRequest
  (build-request-map [request]
    (servlet/build-request-map request)))

(defn ^:internal proxy-handler
  "Returns an Jetty Handler implementation for the given Ring handler."
  [handler]
  (proxy [AbstractHandler] []
    (handle [_ ^Request base-request request response]
      (let [request-map (build-request-map request)
            response-map (handler request-map)]
        (when response-map
          (servlet/update-servlet-response response response-map)
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

(defn- ssl-context-factory
  "Creates a new SslContextFactory instance from a map of options."
  [{:as options
    :keys [keystore keystore-type key-password client-auth
           truststore trust-password truststore-type]}]
  (let [context (SslContextFactory.)]
    (if (string? keystore)
      (.setKeyStorePath context keystore)
      (.setKeyStore context ^java.security.KeyStore keystore))
    (.setKeyStorePassword context key-password)
    (when keystore-type
      (.setKeyStoreType context keystore-type))
    (when truststore
      (.setTrustStore context ^java.security.KeyStore truststore))
    (when trust-password
      (.setTrustStorePassword context trust-password))
    (when truststore-type
      (.setTrustStoreType context truststore-type))
    (case client-auth
      :need (.setNeedClientAuth context true)
      :want (.setWantClientAuth context true)
      nil)
    context))

(defn- create-server
  "Construct a Jetty Server instance."
  [{:as options
    :keys [port max-threads min-threads threadpool-idle-timeout job-queue
           daemon? max-idle-time host ssl? ssl-port h2? h2c?]
    :or {port 80
         max-threads 50
         min-threads 8
         threadpool-idle-timeout 60000
         job-queue nil
         daemon? false
         max-idle-time 200000
         ssl? false}}]
  (let [pool (doto (QueuedThreadPool. (int max-threads)
                                      (int min-threads)
                                      (int threadpool-idle-timeout)
                                      job-queue)
               (.setDaemon daemon?))
        server (doto (Server. pool)
                 (.addBean (ScheduledExecutorScheduler.)))

        http-configuration (http-config options)
        plain-connection-factories [(HttpConnectionFactory. http-configuration)]
        plain-connection-factories (if h2c?
                                     (conj plain-connection-factories
                                           (HTTP2CServerConnectionFactory. http-configuration))
                                     plain-connection-factories)
        http-connector (doto (ServerConnector.
                              ^Server server
                              (into-array ConnectionFactory plain-connection-factories))
                         (.setPort port)
                         (.setHost host)
                         (.setIdleTimeout max-idle-time))

        secure-connection-factory [(HttpConnectionFactory. http-configuration)]
        secure-connection-factory (if h2?
                                    (conj secure-connection-factory
                                          (HTTP2ServerConnectionFactory. http-configuration))
                                    secure-connection-factory)
        https-connector (when (or ssl? ssl-port)
                          (doto (ServerConnector.
                                 ^Server server
                                 ^SslContextFactory(ssl-context-factory options)
                                 (into-array ConnectionFactory secure-connection-factory))
                            (.setPort ssl-port)
                            (.setHost host)
                            (.setIdleTimeout max-idle-time)))

        connectors (if https-connector
                     [http-connector https-connector]
                     [http-connector])
        connectors (into-array connectors)]
    (.setConnectors server connectors)
    server))

(defn ^Server run-jetty
  "
  Start a Jetty webserver to serve the given handler according to the
  supplied options:

  :port - the port to listen on (defaults to 80)
  :host - the hostname to listen on
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
  :max-threads - the maximum number of threads to use (default 50)
  :min-threads - the minimum number of threads to use (default 8)
  :threadpool-idle-timeout - the maximum idle time in milliseconds for a thread (default 60000)
  :job-queue - the job queue to be used by the Jetty threadpool (default is unbounded)
  :max-idle-time  - the maximum idle time in milliseconds for a connection (default 200000)
  :ws-max-idle-time  - the maximum idle time in milliseconds for a websocket connection (default 500000)
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

  "
  [handler {:as options
            :keys [max-threads websockets configurator join?]
            :or {max-threads 50
                 join? true}}]
  (let [^Server s (create-server options)
        ^QueuedThreadPool p (QueuedThreadPool. (int max-threads))
        ring-app-handler (proxy-handler handler)
        ws-handlers (map (fn [[context-path handler]]
                           (doto (ContextHandler.)
                             (.setContextPath context-path)
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
