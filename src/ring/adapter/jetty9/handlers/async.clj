(ns ring.adapter.jetty9.handlers.async
  (:require
    [ring.adapter.jetty9.common :as common]
    [ring.adapter.jetty9.websocket :as ws])
  (:import [org.eclipse.jetty.server Request Response]
           [org.eclipse.jetty.util Callback])
  (:gen-class
    :name ring.adapter.jetty9.handlers.AsyncProxyHandler
    :extends org.eclipse.jetty.server.Handler$Abstract$NonBlocking
    :state state
    :init init
    :constructors {[clojure.lang.IFn
                    clojure.lang.IPersistentMap] []}
    :prefix "-"))

(defn -init
  [ring-handler opts]
  [[] [ring-handler opts]])

(defn -doHandle
  "Asynchronous override for `ServletHandler.doHandle"
  [^ring.adapter.jetty9.handlers.AsyncProxyHandler this
   _
   ^Request request
   ^Response response
   ^Callback callback]
  (try
    (let [[handler options] (.state this)
          async-timeout (:async-timeout options 30000)
          ^AsyncContext context (doto (.startAsync request)
                                  (.setTimeout async-timeout))]
      (handler
        (servlet/build-request-map request)
        (fn [response-map]
          (let [response-map (common/normalize-response response-map)]
            (if-let [ws (common/websocket-upgrade-response? response-map)]
              (ws/upgrade-websocket request response context ws options)
              (servlet/update-servlet-response response context response-map))))
        (fn [^Throwable exception]
          (.sendError response 500 (.getMessage exception))
          (.complete context))))
    (finally
      (.setHandled base-request true))))
