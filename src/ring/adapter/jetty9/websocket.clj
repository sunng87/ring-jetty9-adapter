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

(defn- write-callback
  [write-success write-failed]
  (reify Callback
    (succeed [_]
      (write-success))
    (fail [_ throwable]
      (write-failed throwable))))

(defn build-upgrade-request-map [^ServerUpgradeRequest request]
  (let [base-request-map (build-request-map request)]
    (assoc base-request-map
           :websocket-subprotocols (into [] (.getSubProtocols request))
           :websocket-extensions (into [] (.getExtensions request)))))

(extend-protocol ring-ws/Socket
  Session
  (-send [this msg]
    (cond
      (string? msg) (.sendText this msg (write-callback noop noop))
      (instance? ByteBuffer msg) (.sendBinary this msg (write-callback noop noop))))
  (-send-async [this msg succeed fail]
    (cond
      (string? msg) (.sendText this msg (write-callback succeed fail))
      (instance? ByteBuffer msg) (.sendBinary this msg (write-callback succeed fail))))
  (-ping [this msg]
    (.sendPing this msg (write-callback noop noop)))
  (-pong [this msg]
    (.sendPong this msg (write-callback noop noop)))
  (-close [this status-code reason]
    (.close this status-code reason (write-callback {})))
  (-open? [this]
    (.isOpen this)))

(defn- proxy-ws-adapter
  [listener]
  (let [session (atom nil)]
    (reify Session$Listener$AutoDemanding
      (^void onWebSocketOpen [this ^Session current-session]
       (ring-ws/on-open listener current-session)
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
  {:pre [(map? ws-resp)]}
  (let [container (ServerWebSocketContainer/get (.getContext req))
        websocket-listeners (:ring.websocket/listener ws-resp)
        creator (reify-default-ws-creator websocket-listeners)]
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
