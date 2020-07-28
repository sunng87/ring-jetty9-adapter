(ns ring.adapter.jetty9-test
  (:require [clojure.test :refer :all]
            [ring.adapter.jetty9 :as jetty9]
            [clj-http.client :as client]
            [gniazdo.core :as ws]))

(defn dummy-app [req]
  {:status 200})

(def websocket-handler
  {:on-connect (fn [ws])
   :on-close   (fn [ws status reason])
   :on-error   (fn [ws e])
   :on-text    (fn [ws msg]
                 (jetty9/send! ws msg))
   :on-byte    (fn [ws bytes offset length])})

(defmacro with-jetty [[binding [handler opts]] & body]
  `(let [server# (jetty9/run-jetty ~handler ~opts)
         ~binding server#]
     (try ~@body
          (finally (.stop server#)))))

(deftest jetty9-test
  (with-jetty [server [dummy-app {:port       50524
                                  :join?      false
                                  :websockets {"/path" websocket-handler}}]]
    (is server)
    (let [resp (client/get "http://localhost:50524/")]
      (is (= 200 (:status resp))))))

(deftest keystore-and-truststore-test
  (with-jetty [server [dummy-app {:ssl-port        50524
                                  :http?           false
                                  :ssl             true
                                  :join?           false
                                  :websockets      {"/path" websocket-handler}
                                  :keystore        "dev-resources/test/my-keystore.jks"
                                  :key-password    "password"
                                  :keystore-type   "PKCS12"
                                  :truststore      "dev-resources/test/my-truststore.jks"
                                  :trust-password  "password"
                                  :truststore-type "PKCS12"}]]
    (is server)
    (let [resp (client/get "https://localhost:50524/" {:insecure? true})]
      (is (= 200 (:status resp))))))

(deftest websocket-test
  (with-jetty [server [dummy-app {:port       50524
                                  :join?      false
                                  :websockets {"/path" websocket-handler}}]]
    (is server)
    (let [resp-promise (promise)]
      (with-open [socket (ws/connect "ws://localhost:50524/path/"
                                     :on-receive #(deliver resp-promise %))]
        (ws/send-msg socket "hello")
        (is (= "hello" (deref resp-promise 20000 false)))))))