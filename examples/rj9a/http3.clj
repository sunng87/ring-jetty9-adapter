(ns rj9a.http3
  (:gen-class)
  (:require [ring.adapter.jetty9 :as jetty]))

(defn dummy-app [req] {:body "It works" :status 200})

(defn -main [& args]
  (let [pem-work-dir "target/pem-work/"]
    (.mkdirs (clojure.java.io/file pem-work-dir))
    (jetty/run-jetty dummy-app {:port 5000 :http false :http3? true :ssl-port 5443
                                :http3-pem-work-directory pem-work-dir
                                :keystore "dev-resources/keystore.jks"
                                :key-password "111111"
                                :keystore-type "jks"
                                :sni-host-check? false})))
