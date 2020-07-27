(ns ring.adapter.jetty9.websocket
  (:import [org.eclipse.jetty.server Request Response]
           [org.eclipse.jetty.server.handler AbstractHandler]
           [org.eclipse.jetty.websocket.api
            WebSocketAdapter Session
            UpgradeRequest RemoteEndpoint WriteCallback]
           [org.eclipse.jetty.websocket.api.extensions ExtensionConfig]
           [org.eclipse.jetty.websocket.server WebSocketHandler]
           [org.eclipse.jetty.websocket.servlet
            WebSocketServletFactory WebSocketCreator
            ServletUpgradeRequest ServletUpgradeResponse]
           [clojure.lang IFn]
           [java.nio ByteBuffer]
           [java.util Locale Map])
  (:require [ring.adapter.jetty9.common :refer :all]
            [clojure.string :as string]
            [ring.util.servlet :as servlet]))

(defprotocol WebSocketProtocol
  (send! [this msg] [this msg callback])
  (close! [this])
  (remote-addr [this])
  (idle-timeout! [this ms])
  (connected? [this])
  (req-of [this]))

(defprotocol WebSocketSend
  (-send! [x ws] [x ws callback] "How to encode content sent to the WebSocket clients"))

(def ^:private no-op (constantly nil))

(defn- write-callback
  [{:keys [write-failed write-success]
    :or {write-failed  no-op
         write-success no-op}}]
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
         (.sendString ^RemoteEndpoint (str this) ^WriteCallback (write-callback callback)))))

  ;; "nil" could PING?
  ;; nil
  ;; (-send! [this ws] ()

  )

(extend-protocol RequestMapDecoder
  ServletUpgradeRequest
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
                                                                   "javax.servlet.request.X509Certificate"))}]
      (assoc base-request-map
             :websocket-subprotocols (into [] (.getSubProtocols request))
             :websocket-extensions (into [] (.getExtensions request))))))

(extend-protocol WebSocketProtocol
  WebSocketAdapter
  (send!
    ([this msg]
     (-send! msg this))
    ([this msg callback]
     (-send! msg this callback)))
  (close! [this]
    (.. this (getSession) (close)))
  (remote-addr [this]
    (.. this (getSession) (getRemoteAddress)))
  (idle-timeout! [this ms]
    (.. this (getSession) (setIdleTimeout ^long ms)))
  (connected? [this]
    (. this (isConnected)))
  (req-of [this]
    (build-request-map (.. this (getSession) (getUpgradeRequest)))))

(defn- do-nothing [& args])

(defprotocol IWebSocketAdapter
  (ws-adapter [this]))

(defn proxy-ws-adapter
  [{:as ws-fns
    :keys [on-connect on-error on-text on-close on-bytes]
    :or {on-connect do-nothing
         on-error do-nothing
         on-text do-nothing
         on-close do-nothing
         on-bytes do-nothing}}]
  (proxy [WebSocketAdapter] []
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
      (on-bytes this payload offset len))))

(extend-protocol IWebSocketAdapter
  Map
  (ws-adapter [this]
    (proxy-ws-adapter this))

  WebSocketAdapter
  (ws-adapter [this]
   this))

(defn- reify-default-ws-creator
  [ws]
  (reify WebSocketCreator
    (createWebSocket [this _ _]
      (ws-adapter ws))))

(defn- reify-custom-ws-creator
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
              (.setExtensions resp (mapv #(ExtensionConfig. ^String %) exts)))
            (ws-adapter ws-results)))))))

(defn ^:internal proxy-ws-handler
  "Returns a Jetty websocket handler"
  [ws {:as options
       :keys [ws-max-idle-time
              ws-max-text-message-size]
       :or {ws-max-idle-time 500000
            ws-max-text-message-size 65536}}]
  (proxy [WebSocketHandler] []
    (configure [^WebSocketServletFactory factory]
      (doto (.getPolicy factory)
        (.setIdleTimeout ws-max-idle-time)
        (.setMaxTextMessageSize ws-max-text-message-size))
      (.setCreator factory
                   (if (fn? ws)
                     (reify-custom-ws-creator ws)
                     (reify-default-ws-creator ws))))
    (handle [^String target, ^Request request req ^Response res]
      (let [^WebSocketHandler this this
            ^WebSocketServletFactory wsf (proxy-super getWebSocketFactory)]
        (if (.isUpgradeRequest wsf req res)
          (if (.acceptWebSocket wsf req res)
            (.setHandled request true)
            (when (.isCommitted res)
              (.setHandled request true)))
          (proxy-super handle target request req res))))))
