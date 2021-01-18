(ns ring.adapter.jetty9.websocket-servlet
  (:import [org.eclipse.jetty.websocket.server JettyWebSocketServlet
            JettyWebSocketServletFactory])
  (:require [ring.adapter.jetty9.websocket :as ws])
  (:gen-class
   :extends JettyWebSocketServlet
   :init init
   :state state
   :methods [[configure [JettyWebSocketServletFactory] void]]))


;; options
;; {:as options
;;  :keys [ws-max-idle-time
;;         ws-max-text-message-size]
;;  :or {ws-max-idle-time 500000
;;       ws-max-text-message-size 65536}}
(defn -init [ws-handler options]
  [[] {:ws-handler ws-handler
       :options options}])

(defn -configure [this ^JettyWebSocketServletFactory factory]
  (let [ws-handler (:ws-handler @(.state this))
        {:as options
         :keys [ws-max-idle-time
                ws-max-text-message-size]
         :or {ws-max-idle-time 500000
              ws-max-text-message-size 65536}} (:options @(.state this))]
    (doto factory
      (.setIdleTimeout ws-max-idle-time)
      (.setMaxTextMessageSize ws-max-text-message-size))
    (let [creator (if (map? ws-handler)
                    (ws/reify-default-ws-creator ws-handler)
                    (ws/reify-custom-ws-creator ws-handler))]
      (.setCreator factory creator))))
