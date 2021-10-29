(ns rj9a.websocket-upgrade
  (:gen-class)
  (:require [clojure.string :refer [lower-case]]
            [ring.adapter.jetty9 :as jetty]))

(defonce server (atom nil))

(defn websocket-upgrade? [{:keys [headers]}]
  (and (= "websocket" (lower-case (get headers "upgrade")))
       (= "upgrade" (lower-case (get headers "connection")))))

(defn websocket-upgrade-response [ws-handler]
  {:status 101 ;; http 101 switching protocols
   :headers {"upgrade" "websocket"
             "connection" "upgrade"}
   :ws ws-handler})

(defn my-websocket-handler [_]
  {:on-connect (fn on-connect [_]
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
               (tap> [:ws :error e]))})

(defn handler [req]
  (if (websocket-upgrade? req)
    (websocket-upgrade-response my-websocket-handler)
    {:status 200 :body "hello"}))

(defn start! []
  (when-not @server
    (reset! server (jetty/run-jetty
                    #'handler
                    {:port 5000
                     :join? false
                     :allow-null-path-info true
                     ;; The same ws can also be available via the old regular websocket endpoints.
                     ;; It's added here in this example just for regression testing purposes.
                     :websockets {"/mywebsocket" my-websocket-handler}}))))

(defn stop! []
  (when @server
    (jetty/stop-server @server)
    (reset! server nil)))

(comment
  (start!)
  (stop!))

(defn -main [& _]
  (start!))
