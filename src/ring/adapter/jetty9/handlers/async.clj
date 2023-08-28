(ns ring.adapter.jetty9.handlers.async
  (:require
    [ring.adapter.jetty9.common :as common]
    #_[ring.adapter.jetty9.websocket :as ws])
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

(defn -handle
  "Asynchronous override for `ServletHandler.doHandle"
  [^ring.adapter.jetty9.handlers.AsyncProxyHandler this
   ^Request request
   ^Response response
   ^Callback callback]
  (try
    (let [[handler options] (.state this)
          ;;TODO: async timeout
          ;; async-timeout (:async-timeout options 30000)
          ]
      (handler
        (common/build-request-map request)
        (fn [response-map]
          (let [response-map (common/normalize-response response-map)]
            (common/update-response response response-map)
            #_(if-let [ws (common/websocket-upgrade-response? response-map)]
              (ws/upgrade-websocket request response context ws options)
              (common/update-response response context response-map)))
          (.succeed callback))
        (fn [^Throwable exception]
          (Response/writeError request response callback exception)
          (.fail callback exception))))
    (finally
      true)))
