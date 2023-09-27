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

;; TODO: get websocket headers
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

(defn reify-ws-creator
  [resp-map]
  (reify WebSocketCreator
    (createWebSocket [this req resp cb]
      (let [listener (:ring.websocket/listener resp-map)
            protocol (:ring.websocket/protocol resp-map)]
        (when (some? protocol)
          (.setAcceptedSubProtocol resp protocol))
        #_(when-let [exts (not-empty (:extensions ws-results))]
          (.setExtensions resp (mapv #(if (string? %)
                                        (JettyExtensionConfig. ^String %)
                                        %)
                                     exts)))
        (proxy-ws-adapter listener)))))

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
        creator (reify-ws-creator ws-resp)]
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

(defn ensure-container [^Server server ^ContextHandler context-handler]
  (ServerWebSocketContainer/ensure server context-handler))
