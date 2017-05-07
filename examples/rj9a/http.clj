(ns rj9a.http
  (:gen-class)
  (:require [ring.adapter.jetty9 :as jetty]))

(defn dummy-app [req] {:body "It works" :status 200})

(defn -main [& args]
  (jetty/run-jetty dummy-app {:port 5000}))
