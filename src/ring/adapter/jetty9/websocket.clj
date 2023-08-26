(ns ring.adapter.jetty9.websocket
  (:import [org.eclipse.jetty.server Request Response Server]
           [org.eclipse.jetty.server.handler ContextHandler]
           [org.eclipse.jetty.websocket.api Session Session$Listener Callback]
           [org.eclipse.jetty.websocket.server ServerWebSocketContainer
            WebSocketCreator ServerUpgradeRequest]
           [org.eclipse.jetty.websocket.common JettyExtensionConfig]
           [clojure.lang IFn]
           [java.nio ByteBuffer]
           [java.util Locale]
           [java.time Duration])
  (:require [clojure.string :as string]
            [ring.adapter.jetty9.common :refer [build-request-map
                                                get-headers set-headers! noop]]))

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
  (reify Callback
    (succeed [_]
      (write-success))
    (fail [_ throwable]
      (write-failed throwable))))

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
     (.sendBinary ^Session ws ^ByteBuffer bb ^Callback (write-callback {})))
    ([bb ws callback]
     (.sendBinary ^Session ws ^ByteBuffer bb ^Callback (write-callback callback))))

  String
  (-send!
    ([s ws]
     (.sendText ^Session ws ^String s ^Callback (write-callback {})))
    ([s ws callback]
     (.sendText ^Session ws ^String s ^Callback (write-callback callback))))

  IFn
  (-send! [f ws]
    (f ws))

  Object
  (-send!
    ([this ws]
     (-send! ws (str this)))
    ([this ws callback]
     (-send! ws (str this) callback))))

(extend-protocol WebSocketPing
  (Class/forName "[B")
  (-ping! [ba ws] (-ping! (ByteBuffer/wrap ba) ws))

  ByteBuffer
  (-ping! [bb ws] (.sendPing ^Session ws ^ByteBuffer bb ^Callback (write-callback {})))

  String
  (-ping! [s ws] (-ping! (.getBytes ^String s) ws))

  Object
  (-ping! [o ws] (-ping! (str o) ws)))

(defn build-upgrade-request-map [^ServerUpgradeRequest request]
  (let [base-request-map (build-request-map request)]
    (assoc base-request-map
           :websocket-subprotocols (into [] (.getSubProtocols request))
           :websocket-extensions (into [] (.getExtensions request)))))

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
     (.close this status-code reason (write-callback {}))))
  (remote-addr [this]
    (.getRemoteSocketAddress this))
  (idle-timeout! [this ms]
    (.setIdleTimeout this (java.time.Duration/ofMillis ^long ms)))
  (connected? [this]
    (.isOpen this))
  (req-of [this]
    (build-upgrade-request-map (.getUpgradeRequest this))))

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
  (let [session (atom nil)]
    (reify Session$Listener
      (^void onWebSocketOpen [this ^Session current-session]
       (println "opened")
       (on-connect current-session)
       ;; save session
       (reset! session current-session))
      (^void onWebSocketError [this ^Throwable e]
       (on-error @session e))
      (^void onWebSocketText [this ^String message]
       (println @session message)
       (on-text @session message))
      (^void onWebSocketClose [this ^int status ^String reason]
       (on-close @session status reason))
      (^void onWebSocketBinary [this ^ByteBuffer payload ^Callback cb]
       (on-bytes @session payload))
      (^void onWebSocketPing [this ^ByteBuffer bytebuffer]
       (on-ping @session bytebuffer))
      (^void onWebSocketPong [this ^ByteBuffer bytebuffer]
       (on-pong @session bytebuffer)))))

(defn reify-default-ws-creator
  [ws-fns]
  (reify WebSocketCreator
    (createWebSocket [this _ _ _]
      (proxy-ws-adapter ws-fns))))

(defn reify-custom-ws-creator
  [ws-creator-fn]
  (reify WebSocketCreator
    (createWebSocket [this req resp cb]
      (let [req-map (build-upgrade-request-map req)
            ws-results (ws-creator-fn req-map)]
        (if-let [{:keys [code message headers]} (:error ws-results)]
          (do (set-headers! resp headers)
              (Response/writeError resp cb ^int code ^String message cb))
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
  [^Request req
   ^Response resp
   ^Callback cb
   ws
   {:as _options
    :keys [ws-max-idle-time
           ws-max-text-message-size]
    :or {ws-max-idle-time 500000
         ws-max-text-message-size 65536}}]
  {:pre [(or (map? ws) (fn? ws))]}
  (let [container (ServerWebSocketContainer/get (.getContext req))
        creator (if (map? ws)
                  (reify-default-ws-creator ws)
                  (reify-custom-ws-creator ws))]
    (.setIdleTimeout container (Duration/ofMillis ws-max-idle-time))
    (.setMaxTextMessageSize container ws-max-text-message-size)
    (.upgrade container creator req resp cb)))

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

(defn ensure-container [^Server server ^ContextHandler context-handler]
  (ServerWebSocketContainer/ensure server context-handler))
