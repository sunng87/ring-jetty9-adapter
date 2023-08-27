(ns ring.adapter.jetty9.websocket
  (:import [org.eclipse.jetty.websocket.api
            Session
            RemoteEndpoint WriteCallback WebSocketPingPongListener]
           [org.eclipse.jetty.websocket.server WebSocketServerContainer
            WebSocketCreator ServerUpgradeRequest]
           [org.eclipse.jetty.websocket.common JettyExtensionConfig]
           [jakarta.servlet AsyncContext]
           [jakarta.servlet.http HttpServlet HttpServletRequest HttpServletResponse]
           [clojure.lang IFn]
           [java.nio ByteBuffer]
           [java.util Locale]
           [java.time Duration])
  (:require [clojure.string :as string]
            [ring.adapter.jetty9.common :refer [RequestMapDecoder build-request-map
                                                get-headers set-headers noop]]))

(defprotocol WebSocketProtocol
  (send! [this msg] [this msg callback])
  (ping! [this] [this msg])
  (close! [this] [this status-code reason])
  (remote-addr [this])
  (idle-timeout! [this ms])
  (connected? [this])
  (req-of [this]))

(defprotocol WebSocketSend
  (-send! [x ws] [x ws callback] "How to encode content sent to the WebSocket clients"))

(defprotocol WebSocketPing
  (-ping! [x ws] "How to encode bytes sent with a ping"))

(defn- write-callback
  [{:keys [write-failed write-success]
    :or   {write-failed  noop
           write-success noop}}]
  (reify WriteCallback
    (writeFailed [_ throwable]
      (write-failed throwable))
    (writeSuccess [_]
      (write-success))))

(extend-protocol WebSocketSend
  (Class/forName "[B")
  (-send!
    ([ba ws]
     (-send! (ByteBuffer/wrap ba) ws))
    ([ba ws callback]
     (-send! (ByteBuffer/wrap ba) ws callback)))

  ByteBuffer
  (-send!
    ([bb ws]
     (-> ^WebSocketAdapter ws .getRemote (.sendBytes ^ByteBuffer bb)))
    ([bb ws callback]
     (-> ^WebSocketAdapter ws .getRemote (.sendBytes ^ByteBuffer bb ^WriteCallback (write-callback callback)))))

  String
  (-send!
    ([s ws]
     (-> ^WebSocketAdapter ws .getRemote (.sendString ^String s)))
    ([s ws callback]
     (-> ^WebSocketAdapter ws .getRemote (.sendString ^String s ^WriteCallback (write-callback callback)))))

  IFn
  (-send! [f ws]
    (-> ^WebSocketAdapter ws .getRemote f))

  Object
  (send!
    ([this ws]
     (-> ^WebSocketAdapter ws .getRemote
         (.sendString ^RemoteEndpoint (str this))))
    ([this ws callback]
     (-> ^WebSocketAdapter ws .getRemote
         (.sendString ^RemoteEndpoint (str this) ^WriteCallback (write-callback callback))))))

(extend-protocol WebSocketPing
  (Class/forName "[B")
  (-ping! [ba ws] (-ping! (ByteBuffer/wrap ba) ws))

  ByteBuffer
  (-ping! [bb ws] (-> ^WebSocketAdapter ws .getRemote (.sendPing ^ByteBuffer bb)))

  String
  (-ping! [s ws] (-ping! (.getBytes ^String s) ws))

  Object
  (-ping! [o ws] (-ping! (str o) ws)))

;; TODO:
(extend-protocol RequestMapDecoder
  ServerUpgradeRequest
  (build-request-map [request]
    (let [servlet-request (.getHttpServletRequest request)
          base-request-map {:server-port (.getServerPort servlet-request)
                            :server-name (.getServerName servlet-request)
                            :remote-addr (.getRemoteAddr servlet-request)
                            :uri (.getRequestURI servlet-request)
                            :query-string (.getQueryString servlet-request)
                            :scheme (keyword (.getScheme servlet-request))
                            :request-method (keyword (.toLowerCase (.getMethod servlet-request) Locale/ENGLISH))
                            :protocol (.getProtocol servlet-request)
                            :headers (get-headers servlet-request)
                            :ssl-client-cert (first (.getAttribute servlet-request
                                                                   "jakarta.servlet.request.X509Certificate"))}]
      (assoc base-request-map
             :websocket-subprotocols (into [] (.getSubProtocols request))
             :websocket-extensions (into [] (.getExtensions request))))))

(extend-protocol WebSocketProtocol
  Session
  (send!
    ([this msg]
     (-send! msg this))
    ([this msg callback]
     (-send! msg this callback)))
  (ping!
    ([this]
     (-ping! (ByteBuffer/allocate 0) this))
    ([this msg]
     (-ping! msg this)))
  (close!
    ([this]
     (.close this))
    ([this status-code reason]
     (.close this status-code reason)))
  (remote-addr [this]
    (.getRemoteAddress this))
  (idle-timeout! [this ms]
    (.setIdleTimeout this (java.time.Duration/ofMillis ^long ms)))
  (connected? [this]
    (.isOpen this))
  (req-of [this]
    (build-request-map (.getUpgradeRequest this))))

(defn- proxy-ws-adapter
  [{:as _
    :keys [on-connect on-error on-text on-close on-bytes on-ping on-pong]
    :or {on-connect noop
         on-error noop
         on-text noop
         on-close noop
         on-bytes noop
         on-ping noop
         on-pong noop}}]
  (proxy [WebSocketAdapter WebSocketPingPongListener] []
    (onWebSocketConnect [^Session session]
      (let [^WebSocketAdapter this this]
        (proxy-super onWebSocketConnect session))
      (on-connect this))
    (onWebSocketError [^Throwable e]
      (on-error this e))
    (onWebSocketText [^String message]
      (on-text this message))
    (onWebSocketClose [statusCode ^String reason]
      (let [^WebSocketAdapter this this]
        (proxy-super onWebSocketClose statusCode reason))
      (on-close this statusCode reason))
    (onWebSocketBinary [^bytes payload offset len]
      (on-bytes this payload offset len))
    (onWebSocketPing [^ByteBuffer bytebuffer]
      (on-ping this bytebuffer))
    (onWebSocketPong [^ByteBuffer bytebuffer]
      (on-pong this bytebuffer))))

(defn reify-default-ws-creator
  [ws-fns]
  (reify JettyWebSocketCreator
    (createWebSocket [this _ _]
      (proxy-ws-adapter ws-fns))))

(defn reify-custom-ws-creator
  [ws-creator-fn]
  (reify WebSocketCreator
    (createWebSocket [this req resp]
      (let [req-map (build-request-map req)
            ws-results (ws-creator-fn req-map)]
        (if-let [{:keys [code message headers]} (:error ws-results)]
          (do (set-headers resp headers)
              (.sendError resp code message))
          (do
            (when-let [sp (:subprotocol ws-results)]
              (.setAcceptedSubProtocol resp sp))
            (when-let [exts (not-empty (:extensions ws-results))]
              (.setExtensions resp (mapv #(if (string? %)
                                            (JettyExtensionConfig. ^String %)
                                            %)
                                         exts)))
            (proxy-ws-adapter ws-results)))))))

(defn upgrade-websocket
  ([req res ws options]
   (upgrade-websocket req res nil ws options))
  ([^HttpServletRequest req
    ^HttpServletResponse res
    ^AsyncContext async-context
    ws
    {:as _options
     :keys [ws-max-idle-time
            ws-max-text-message-size]
     :or {ws-max-idle-time 500000
          ws-max-text-message-size 65536}}]
   {:pre [(or (map? ws) (fn? ws))]}
   (let [creator (if (map? ws)
                   (reify-default-ws-creator ws)
                   (reify-custom-ws-creator ws))
         container (JettyWebSocketServerContainer/getContainer (.getServletContext req))]
     (.setIdleTimeout container (Duration/ofMillis ws-max-idle-time))
     (.setMaxTextMessageSize container ws-max-text-message-size)
     (.upgrade container creator req res)
     (when async-context
       (.complete async-context)))))

#_(defn proxy-ws-servlet [ws options]
  (ServletHolder.
   (proxy [HttpServlet] []
     (doGet [req res]
       (upgrade-websocket req res ws options)))))

(defn ws-upgrade-request?
  "Checks if a request is a websocket upgrade request.

   It is a websocket upgrade request when it contains the following headers:
   - connection: upgrade
   - upgrade: websocket
  "
  [{:keys [headers]}]
  (let [upgrade    (get headers "upgrade")
        connection (get headers "connection")]
    (and upgrade
         connection
         (string/includes? (string/lower-case upgrade) "websocket")
         (string/includes? (string/lower-case connection) "upgrade"))))

(defn ws-upgrade-response
  "Returns a websocket upgrade response.

   ws-handler must be a map of handler fns:
   {:on-connect #(create-fn %)               ; ^WebSocketAdapter ws
    :on-text   #(text-fn % %2)               ; ^WebSocketAdapter ws message
    :on-bytes  #(binary-fn % %2 %3 %4)       ; ^WebSocketAdapter ws payload offset len
    :on-close  #(close-fn % %2 %3)           ; ^WebSocketAdapter ws statusCode reason
    :on-error  #(error-fn % %2)}             ; ^WebSocketAdapter ws e
   or a custom creator function take upgrade request as parameter and returns a handler fns map,
   negotiated subprotocol and extensions (or error info).

   The response contains HTTP status 101 (Switching Protocols)
   and the following headers:
   - connection: upgrade
   - upgrade: websocket
   "
  [ws-handler]
  {:status 101 ;; http 101 switching protocols
   :headers {"upgrade" "websocket"
             "connection" "upgrade"}
   :ws ws-handler})
