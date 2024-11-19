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

(defn -handle
  "Asynchronous override for `Handler$Abstract$NonBlocking/handle"
  [^ring.adapter.jetty9.handlers.AsyncProxyHandler this
   ^Request request
   ^Response response
   ^Callback callback]
  (let [[handler options] (.state this)
        ;;TODO: async timeout
        ;; async-timeout (:async-timeout options 30000)
        ]
    (handler
     (common/build-request-map request)
     (fn [response-map]
       (let [response-map (common/normalize-response response-map)]
         (if (common/websocket-upgrade-response? response-map)
           (ws/upgrade-websocket request response callback response-map options)
           (common/update-response request response response-map)))
       (.succeeded callback))
     (fn [^Throwable exception]
       (Response/writeError request response callback exception)
       (.failed callback exception))))

  true)
