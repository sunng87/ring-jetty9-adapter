(ns ring.adapter.jetty9.websocket
  (:import [org.eclipse.jetty.server Request Response Server]
           [org.eclipse.jetty.server.handler ContextHandler]
           [org.eclipse.jetty.websocket.api Session Session$Listener$AutoDemanding Callback]
           [org.eclipse.jetty.websocket.server ServerWebSocketContainer
            WebSocketCreator ServerUpgradeRequest WebSocketUpgradeHandler]
           [org.eclipse.jetty.websocket.common JettyExtensionConfig]
           [clojure.lang IFn]
           [java.nio ByteBuffer]
           [java.util Locale]
           [java.time Duration])
  (:require [clojure.string :as string]
            [ring.websocket :as ring-ws]
            [ring.adapter.jetty9.common :refer [build-request-map
                                                get-headers set-headers! noop]]))

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
;;TODO
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
;;TODO
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

(extend-protocol ring-ws/Socket
  Session
  (-send [this msg]
    (-send! msg this))
  (-send-async [this msg succeed fail]
    ;;TODO
    )
  (-ping [this msg]
    (-ping! msg this))
  (-pong [this msg]
    (-pong! msg this))
  (-close [this status-code reason]
    (.close this status-code reason (write-callback {})))
  (-open? [this]
    (.isOpen this)))

(defn- proxy-ws-adapter
  [listener]
  (let [session (atom nil)]
    (reify Session$Listener$AutoDemanding
      (^void onWebSocketOpen [this ^Session current-session]
       (ring-ws/on-connect listener current-session)
       ;; save session
       (reset! session current-session))
      (^void onWebSocketError [this ^Throwable e]
       (ring-ws/on-error listener @session e))
      (^void onWebSocketText [this ^String message]
       (ring-ws/on-message listener @session message))
      (^void onWebSocketClose [this ^int status ^String reason]
       (ring-ws/on-close listener @session status reason))
      (^void onWebSocketBinary [this ^ByteBuffer payload ^Callback cb]
       (ring-ws/on-message listener @session payload))
      (^void onWebSocketPing [this ^ByteBuffer bytebuffer]
       )
      (^void onWebSocketPong [this ^ByteBuffer bytebuffer]
       (ring-ws/on-pong listener @session bytebuffer)))))

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
   ws-resp
   {:as _options
    :keys [ws-max-idle-time
           ws-max-text-message-size]
    :or {ws-max-idle-time 500000
         ws-max-text-message-size 65536}}]
  {:pre [(map? ws)]}
  (let [container (ServerWebSocketContainer/get (.getContext req))
        websocket-resp (:ring.websocket/listener ws-resp)
        creator (reify-default-ws-creator ws)]
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
