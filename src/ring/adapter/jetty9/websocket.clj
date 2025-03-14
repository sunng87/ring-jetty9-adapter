(ns ring.adapter.jetty9.websocket
  (:import [org.eclipse.jetty.server Request Response Server]
           [org.eclipse.jetty.server.handler ContextHandler]
           [org.eclipse.jetty.websocket.api Session Session$Listener$AutoDemanding Callback]
           [org.eclipse.jetty.websocket.server ServerWebSocketContainer
            WebSocketCreator]
           [java.nio ByteBuffer]
           [java.time Duration])
  (:require [clojure.string :as string]
            [ring.websocket.protocols :as ring-ws]
            [ring.adapter.jetty9.common :refer [noop]]))

(defn- write-callback
  [write-success write-failed]
  (reify Callback
    (succeed [_]
      (write-success))
    (fail [_ throwable]
      (write-failed throwable))))

(extend-type Session
  ring-ws/Socket
  (-send [this msg]
    (if (instance? CharSequence msg)
      (.sendText this msg (write-callback noop noop))
      (.sendBinary this msg (write-callback noop noop))))
  (-ping [this msg]
    (.sendPing this msg (write-callback noop noop)))
  (-pong [this msg]
    (.sendPong this msg (write-callback noop noop)))
  (-close [this status-code reason]
    (.close this status-code reason (write-callback noop noop)))
  (-open? [this]
    (.isOpen this))

  ring-ws/AsyncSocket
  (-send-async [this msg succeed fail]
    (if (instance? CharSequence msg)
      (.sendText this msg (write-callback succeed fail))
      (.sendBinary this msg (write-callback succeed fail)))))

(defn- proxy-ws-adapter
  [listener]
  (let [session (atom nil)]
    (reify Session$Listener$AutoDemanding
      (^void onWebSocketOpen [_this ^Session current-session]
        (ring-ws/on-open listener current-session)
       ;; save session
        (reset! session current-session))
      (^void onWebSocketError [_this ^Throwable e]
        (ring-ws/on-error listener @session e))
      (^void onWebSocketText [_this ^String message]
        (ring-ws/on-message listener @session message))
      (^void onWebSocketClose [_this ^int status ^String reason]
        (ring-ws/on-close listener @session status reason))
      (^void onWebSocketBinary [_this ^ByteBuffer payload ^Callback _cb]
        (ring-ws/on-message listener @session payload))
      (^void onWebSocketPing [_this ^ByteBuffer bytebuffer]
        (when (satisfies? ring-ws/PingListener listener)
          (ring-ws/on-ping listener @session bytebuffer)))
      (^void onWebSocketPong [_this ^ByteBuffer bytebuffer]
        (ring-ws/on-pong listener @session bytebuffer)))))

(defn reify-ws-creator
  [resp-map]
  (reify WebSocketCreator
    (createWebSocket [_this _req resp _cb]
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
           ws-max-frame-size
           ws-max-binary-message-size
           ws-max-text-message-size
           ws-configurator]
    :or {ws-configurator (constantly nil)}}]
  {:pre [(map? ws-resp)]}
  (let [container (ServerWebSocketContainer/get (.getContext req))
        creator (reify-ws-creator ws-resp)]
    (when ws-max-idle-time
      (.setIdleTimeout container (Duration/ofMillis ws-max-idle-time)))
    (when ws-max-frame-size
      (.setMaxFrameSize container ws-max-frame-size))
    (when ws-max-binary-message-size
      (.setMaxBinaryMessageSize container ws-max-binary-message-size))
    (when ws-max-text-message-size
      (.setMaxTextMessageSize container ws-max-text-message-size))
    (ws-configurator container)
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
