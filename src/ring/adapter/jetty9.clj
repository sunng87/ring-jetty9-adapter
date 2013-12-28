(ns ring.adapter.jetty9
  "Adapter for the Jetty 9 server, with websocket support.
Derived from ring.adapter.jetty"
  (:import (org.eclipse.jetty.server
             Handler Server Request ServerConnector
             HttpConfiguration HttpConnectionFactory SslConnectionFactory)
           (org.eclipse.jetty.server.handler
             HandlerCollection AbstractHandler ContextHandler)
           (org.eclipse.jetty.util.thread
             QueuedThreadPool ScheduledExecutorScheduler)
           (org.eclipse.jetty.util.ssl SslContextFactory)
           (org.eclipse.jetty.websocket.server WebSocketHandler)
           (org.eclipse.jetty.websocket.servlet WebSocketServletFactory WebSocketCreator ServletUpgradeRequest
                                                ServletUpgradeResponse)
           (javax.servlet.http HttpServletRequest HttpServletResponse)
           (org.eclipse.jetty.websocket.api WebSocketAdapter Session))
  (:require [ring.util.servlet :as servlet]))

(defn- proxy-ws-adapter
  [ws-fns ring-request-map]
  (proxy [WebSocketAdapter] []
    (onWebSocketConnect [^Session session]
      (proxy-super onWebSocketConnect session)
      ((:connect-fn ws-fns) ring-request-map session))
    ;(.. session getRemote (sendString "hahahaha!!@!")))
    (onWebSocketError [^Throwable error]
      ((:error-fn ws-fns) ring-request-map error))
    (onWebSocketText [^String message]
      ((:text-fn ws-fns) ring-request-map message))
    ;(let [session (.getSession this)]
    ;  (.. session getRemote (sendString "hahahaha!!@!"))))
    (onWebSocketClose [statusCode ^String reason]
      (proxy-super onWebSocketClose statusCode reason)
      ((:close-fn ws-fns) ring-request-map statusCode reason))
    (onWebSocketBinary [ring-request-map ^byte [] payload offset len]
      ((:binary-fn ws-fns) ring-request-map payload offset len))))

(defn- proxy-ws-creator
  [ws-fns]
  (proxy [WebSocketCreator] []
    (createWebSocket [^ServletUpgradeRequest upRequest ^ServletUpgradeResponse upResponse]
      (let [ring-request-map (.getServletAttribute upRequest "ring-request-map")]
        (proxy-ws-adapter ws-fns ring-request-map)))))

(defn- proxy-ws-handler
  "Returns a Jetty websocket handler"
  [ws-fns]
  (proxy [WebSocketHandler] []
    (configure [^WebSocketServletFactory factory]
      (.setCreator factory (proxy-ws-creator ws-fns)))
    (handle [^java.lang.String target, ^org.eclipse.jetty.server.Request baseRequest, ^javax.servlet.http.HttpServletRequest request, ^javax.servlet.http.HttpServletResponse response]
      (let [request-map (servlet/build-request-map request)]
        (.setAttribute request "ring-request-map" request-map))
      (proxy-super handle target baseRequest request response))))

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
    (when (options :truststore)
      (.setTrustStore context ^java.security.KeyStore (options :truststore)))
    (when (options :trust-password)
      (.setTrustStorePassword context (options :trust-password)))
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
                               (into-array [(HttpConnectionFactory. http-configuration)]))
                         (.setPort (options :port 80))
                         (.setHost (options :host)))

        https-connector (when (or (options :ssl?) (options :ssl-port))
                          (doto (ServerConnector.
                                  server
                                  (SslConnectionFactory.
                                    (ssl-context-factory options)
                                    "http/1.1")
                                  (into-array [(HttpConnectionFactory. http-configuration)]))
                            (.setPort (options :ssl-port 443))
                            (.setHost (options :host))))

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
:key-password - the password to the keystore
:truststore - a truststore to use for SSL connections
:trust-password - the password to the truststore
:max-threads - the maximum number of threads to use (default 50)
:client-auth - SSL client certificate authenticate, may be set to :need, :want or :none (defaults to :none)
:websockets - a map of websockets handler {\"/context\" {}}"
  [handler options]
  (let [^Server s (create-server (dissoc options :configurator))
        ^QueuedThreadPool p (QueuedThreadPool. ^Integer (options :max-threads 50))
        ring-app-handler (proxy-handler handler)
        ws-handlers (map #(doto (ContextHandler.)
                           (.setContextPath (key %))
                           (.setHandler (proxy-ws-handler (val %))))
                         (or (:websockets options) []))
        contexts (doto (HandlerCollection.)
                   (.setHandlers
                     (into-array Handler (conj ws-handlers ring-app-handler))))]
    (.setHandler s contexts)
    (when-let [configurator (:configurator options)]
      (configurator s))
    (.start s)
    (when (:join? options true)
      (.join s))
    s))
