(ns ring.adapter.jetty9.handlers.sync
  (:require
    [ring.adapter.jetty9.common :as common]
    [ring.adapter.jetty9.websocket :as ws])
  (:import [org.eclipse.jetty.server Request Response]
           [org.eclipse.jetty.util Callback])
  (:gen-class
    :name ring.adapter.jetty9.handlers.SyncProxyHandler
    :extends org.eclipse.jetty.server.Handler$Abstract
    :state state
    :init init
    :constructors {[clojure.lang.IFn
                    clojure.lang.IPersistentMap] []}
    :prefix "-"))

(defn -init
  [ring-handler opts]
  [[] [ring-handler opts]])

(defn -handle
  "Synchronous override for `ServletHandler.doHandle"
  [^ring.adapter.jetty9.handlers.SyncProxyHandler this
   ^Request request
   ^Response response
   ^Callback callback]
  (try
    (let [[handler options] (.state this)
          response-map (-> request
                           common/build-request-map
                           handler
                           common/normalize-response)]
      (if-let [ws (common/websocket-upgrade-response? response-map)]
        (ws/upgrade-websocket request response callback ws options)
        (do
          (common/update-response response response-map)
          true)))
    (catch Throwable e
      (Response/writeError request response callback e)
      true)))
