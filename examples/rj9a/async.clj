(ns rj9a.async
  (:gen-class)
  (:require [ring.adapter.jetty9 :as jetty]))

(defn dummy-app [req send-response raise-error]
  (send-response {:body "It works" :status 200}))

(defn -main [& args]
  (jetty/run-jetty dummy-app {:port 5000 :async? true}))
