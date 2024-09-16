(ns rj9a.http2
  (:gen-class)
  (:require [ring.adapter.jetty9 :as jetty]))

(defn dummy-app [req] {:body "It works" :status 200})

(defn -main [& args]
  (jetty/run-jetty dummy-app {:port 5000 :h2c? true :h2? true :ssl? true :ssl-port 5443
                              :keystore "dev-resources/keystore.jks"
                              :key-password "111111"
                              :keystore-type "jks"
                              :sni-host-check? false}))
