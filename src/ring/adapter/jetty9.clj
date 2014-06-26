(ns ring.adapter.jetty9
  "Adapter for the Jetty 9 server, with websocket support.
Derived from ring.adapter.jetty"
  (:import (org.eclipse.jetty.server
            Handler Server Request ServerConnector
            HttpConfiguration HttpConnectionFactory SslConnectionFactory ConnectionFactory)
           (org.eclipse.jetty.server.handler
            HandlerCollection AbstractHandler ContextHandler HandlerList)
           (org.eclipse.jetty.util.thread
            QueuedThreadPool ScheduledExecutorScheduler)
           (org.eclipse.jetty.util.ssl SslContextFactory)
           (org.eclipse.jetty.websocket.server WebSocketHandler)
           (org.eclipse.jetty.websocket.servlet WebSocketServletFactory WebSocketCreator ServletUpgradeRequest
                                                ServletUpgradeResponse)
           (javax.servlet.http HttpServletRequest HttpServletResponse)
           (org.eclipse.jetty.websocket.api WebSocketAdapter Session)
           [java.nio ByteBuffer])
  (:require [ring.util.servlet :as servlet]))

(defprotocol WebSocketProtocol
  (send-text [this msg])
  (send-bytes [this bytes])
  (close [this])
  (remote-addr [this])
  (idle-timeout [this ms]))

(extend-protocol WebSocketProtocol
  WebSocketAdapter
  (send-text [this msg]
    (.. this (getRemote) (sendString ^String msg)))
  (send-bytes [this bytes]
    (.. this (getRemote) (sendBytes ^ByteBuffer bytes)))
  (close [this]
    (.. this (getSession) (close)))
  (remote-addr [this]
    (.. this (getSession) (getRemoteAddress)))
  (idle-timeout [this ms]
    (.. this (getSession) (setIdleTimeout ^long ms))))

(defn- do-nothing [& args])

(defn- proxy-ws-adapter
  [ws-fns]
  (proxy [WebSocketAdapter] []
    (onWebSocketConnect [^Session session]
      (proxy-super onWebSocketConnect session)
      ((:on-connect ws-fns do-nothing) this))
    (onWebSocketError [^Throwable e]
      ((:on-error ws-fns do-nothing) this e))
    (onWebSocketText [^String message]
      ((:on-text ws-fns do-nothing) this message))
    (onWebSocketClose [statusCode ^String reason]
      (proxy-super onWebSocketClose statusCode reason)
      ((:on-close ws-fns do-nothing) this))
    (onWebSocketBinary [^bytes payload offset len]
      ((:on-bytes ws-fns do-nothing) this payload offset len))))

(defn- reify-ws-creator
  [ws-fns]
  (reify WebSocketCreator
    (createWebSocket [this _ _]
      (proxy-ws-adapter ws-fns))))

(defn- proxy-ws-handler
  "Returns a Jetty websocket handler"
  [ws-fns options]
  (proxy [WebSocketHandler] []
    (configure [^WebSocketServletFactory factory]
      (-> (.getPolicy factory)
          (.setIdleTimeout (options :ws-max-idle-time 500000)))
      (.setCreator factory (reify-ws-creator ws-fns)))))

(defn- proxy-handler
  "Returns an Jetty Handler implementation for the given Ring handler."
  [handler]
  (proxy [AbstractHandler] []
    (handle [_ ^Request base-request request response]
      (let [request-map (servlet/build-request-map request)
            response-map (handler request-map)]
        (when response-map
          (servlet/update-servlet-response response response-map)
          (.setHandled base-request true))))))

(defn- http-config [options]
  "Creates jetty http configurator"
  (doto (HttpConfiguration.)
    (.setSecureScheme "https")
    (.setSecurePort (options :ssl-port 443))
    (.setOutputBufferSize 32768)
    (.setRequestHeaderSize 8192)
    (.setResponseHeaderSize 8192)
    (.setSendServerVersion true)
    (.setSendDateHeader false)
    (.setHeaderCacheSize 512)))

(defn- ssl-context-factory
  "Creates a new SslContextFactory instance from a map of options."
  [options]
  (let [context (SslContextFactory.)]
    (if (string? (options :keystore))
      (.setKeyStorePath context (options :keystore))
      (.setKeyStore context ^java.security.KeyStore (options :keystore)))
    (.setKeyStorePassword context (options :key-password))
    (when (options :keystore-type)
      (.setKeyStoreType context (options :keystore-type)))
    (when (options :truststore)
      (.setTrustStore context ^java.security.KeyStore (options :truststore)))
    (when (options :trust-password)
      (.setTrustStorePassword context (options :trust-password)))
    (when (options :truststore-type)
      (.setTrustStoreType context (options :truststore-type)))
    (case (options :client-auth)
      :need (.setNeedClientAuth context true)
      :want (.setWantClientAuth context true)
      nil)
    context))

(defn- create-server
  "Construct a Jetty Server instance."
  [options]
  (let [pool (doto (QueuedThreadPool. ^Integer (options :max-threads 50))
               (.setDaemon (:daemon? options false)))
        server (doto (Server. pool)
                 (.addBean (ScheduledExecutorScheduler.)))

        http-configuration (http-config options)
        http-connector (doto (ServerConnector.
                              ^Server server
                              (into-array ConnectionFactory [(HttpConnectionFactory. http-configuration)]))
                         (.setPort (options :port 80))
                         (.setHost (options :host))
                         (.setIdleTimeout (options :max-idle-time 200000)))

        https-connector (when (or (options :ssl?) (options :ssl-port))
                          (doto (ServerConnector.
                                 ^Server server
                                 (ssl-context-factory options)
                                 (into-array ConnectionFactory [(HttpConnectionFactory. http-configuration)]))
                            (.setPort (options :ssl-port 443))
                            (.setHost (options :host))
                            (.setIdleTimeout (options :max-idle-time 200000))))

        connectors (if https-connector
                     [http-connector https-connector]
                     [http-connector])
        connectors (into-array connectors)]
    (.setConnectors server connectors)
    server))

(defn ^Server run-jetty
  "Start a Jetty webserver to serve the given handler according to the
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
:truststore - a truststore to use for SSL connections
:truststore-type - the format of trust store
:trust-password - the password to the truststore
:max-threads - the maximum number of threads to use (default 50)
:max-idle-time  - the maximum idle time in milliseconds for a connection (default 200000)
:ws-max-idle-time  - the maximum idle time in milliseconds for a websocket connection (default 500000)
:client-auth - SSL client certificate authenticate, may be set to :need, :want or :none (defaults to :none)
:websockets - a map from context path to a map of handler fns:

 {\"/context\" {:create-fn  #(create-fn %)              ; ring-request-map
                :connect-fn #(connect-fn % %2 %3)       ; ring-request-map ^Session ws-conn ring-session
                :text-fn    #(text-fn % %2 %3 %4)       ; ring-request-map ^Session ws-session ring-session message
                :binary-fn  #(binary-fn % %2 %3 %4 %5 %6)  ; ring-request-map ^Session ws-session ring-session payload offset len
                :close-fn   #(close-fn % %2 %3 %4)      ; ring-request-map ring-session statusCode reason
                :error-fn   #(error-fn % %2 %3)}}       ; ring-request-map ring-session e

              create-fn is a ring handler that runs inside WebSocketCreator, and allows the developer to authenticate
              and/or authorize the connection. If it returns status 200, the socket will be created. Also, if create-fn
              sets the key :inner-session, this session map will be passed along to all the rest of the websocket
              handlers."
  [handler options]
  (let [^Server s (create-server (dissoc options :configurator))
        ^QueuedThreadPool p (QueuedThreadPool. ^Integer (options :max-threads 50))
        ring-app-handler (proxy-handler handler)
        ws-handlers (map #(doto (ContextHandler.)
                            (.setContextPath (key %))
                            (.setHandler (proxy-ws-handler (val %) options)))
                         (or (:websockets options) []))
        contexts (doto (HandlerList.)
                   (.setHandlers
                    (into-array Handler (reverse (conj ws-handlers ring-app-handler)))))]
    (.setHandler s contexts)
    (when-let [configurator (:configurator options)]
      (configurator s))
    (.start s)
    (when (:join? options true)
      (.join s))
    s))
