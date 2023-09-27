(ns rj9a.websocket
  (:gen-class)
  (:require [ring.adapter.jetty9 :as jetty]
            [ring.websocket :as ringws]))

(defn my-websocket-handler [upgrade-request]
  (let [provided-subprotocols (:websocket-subprotocols upgrade-request)
        provided-extensions (:websocket-extensions upgrade-request)]
    {:ring.websocket/listener (reify ringws/Listener
                                (on-open [this socket] (tap> [:ws :connect]))
                                (on-message [this socket message]
                                  (tap> [:ws :msg message])
                                  (ringws/-send socket (str "echo: " message)))
                                (on-close [this socket status-code reason]
                                  (tap> [:ws :close status-code reason]))
                                (on-pong [this socket data]
                                  (tap> [:ws :pong]))
                                (on-error [this socket error]
                                  (.printStackTrace error)
                                  (tap> [:ws :error error])))
     :ring.websocket/protocol (first provided-subprotocols)}))

(defn handler [req]
  (if (jetty/ws-upgrade-request? req)
    (my-websocket-handler req)
    {:status 200 :body "hello"}))

(defn async-handler [request send-response _]
  (send-response
   (if (jetty/ws-upgrade-request? request)
     (my-websocket-handler request)
     {:status 200 :body "hello"})))

(defonce server (atom nil))

(defn start! [async?]
  (when-not @server
    (reset! server (jetty/run-jetty
                    (if async? #'async-handler #'handler)
                    {:port 5000
                     :join? false
                     :async? async?
                     :allow-null-path-info true}))))

(defn stop! []
  (when @server
    (jetty/stop-server @server)
    (reset! server nil)))

(comment
  (start! false)
  (stop!))

(defn -main [& _]
  (start! false))
