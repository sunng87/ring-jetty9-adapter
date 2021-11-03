(ns rj9a.websocket
  (:gen-class)
  (:require [ring.adapter.jetty9 :as jetty]))

(defn my-websocket-handler [upgrade-request]
  (let [provided-subprotocols (:websocket-subprotocols upgrade-request)
        provided-extensions (:websocket-extensions upgrade-request)]
    {;; provide websocket callbacks
     :on-connect (fn on-connect [_]
                   (tap> [:ws :connect]))
     :on-text (fn on-text [ws text-message]
                (tap> [:ws :msg text-message])
                (jetty/send! ws (str "echo: " text-message)))
     :on-bytes (fn on-bytes [_ _ _ _]
                 (tap> [:ws :bytes]))
     :on-close (fn on-close [_ status-code reason]
                 (tap> [:ws :close status-code reason]))
     :on-ping (fn on-ping [ws payload]
                (tap> [:ws :ping])
                (jetty/send! ws payload))
     :on-pong (fn on-pong [_ _]
                (tap> [:ws :pong]))
     :on-error (fn on-error [_ e]
                 (tap> [:ws :error e]))
     ;; select subprotocol
     :subprotocol (first provided-subprotocols)
     ;; exntension negotiation
     :extentions provided-extensions}))

(defn handler [req]
  (if (jetty/ws-upgrade-request? req)
    (jetty/ws-upgrade-response my-websocket-handler)
    {:status 200 :body "hello"}))

(defn async-handler [request send-response _]
  (send-response
   (if (jetty/ws-upgrade-request? request)
     (jetty/ws-upgrade-response my-websocket-handler)
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
