(ns ring.adapter.jetty9-test
  (:require [clojure.test :refer :all]
            [ring.adapter.jetty9 :as jetty9]
            [clj-http.client :as client]
            [less.awful.ssl :as less-ssl]
            #_[gniazdo.core :as ws]))

(defn dummy-app [req]
  {:status 200})

(def websocket-handler
  {:on-connect (fn [ws])
   :on-close   (fn [ws status reason])
   :on-error   (fn [ws e])
   :on-text    (fn [ws msg]
                 (jetty9/send! ws msg))
   :on-byte    (fn [ws bytes offset length])})
(defmacro with-jetty
  [[sym [handler opts]] & body]
  `(let [{stop!# :stop-jetty
         ~sym  :server} (->> (assoc ~opts :lifecycle-start (partial println "JETTY START")
                                          :lifecycle-end (partial println "JETTY END"))
                             (jetty9/run-jetty ~handler))]
    (try ~@body
         (finally (stop!#)))))

(deftest jetty9-test
  (with-jetty [server [dummy-app {:port       50524
                                  :join?      false
                                  :websockets {"/path" websocket-handler}}]]
    (is server)
    (let [resp (client/get "http://localhost:50524/")]
      (is (= 200 (:status resp))))))

(defn ssl-context []
  (less-ssl/ssl-context "dev-resources/test/key.pem"
                        "dev-resources/test/cert.pem"
                        "dev-resources/test/cert.pem"))

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

(deftest ssl-context-test
  (with-jetty [server [dummy-app {:ssl-port        50524
                                  :http?           false
                                  :ssl?            true
                                  :join?           false
                                  :websockets      {"/path" websocket-handler}
                                  :ssl-context     (ssl-context)}]]
    (is server)
    (let [resp (client/get "https://localhost:50524/" {:insecure? true})]
      (is (= 200 (:status resp))))
    (let [resp (client/get "https://localhost:50524/"
                           {:keystore "dev-resources/test/my-keystore.jks"
                            :keystore-pass "password"
                            :trust-store "dev-resources/test/my-truststore.jks"
                            :trust-store-pass "password"})]
      (is (= 200 (:status resp))))
    (is (thrown-with-msg?
         Exception
         #"unable to find valid certification path to requested target"
         (client/get "https://localhost:50524/")))))

#_(deftest websocket-test
    (with-jetty [server [dummy-app {:port       50524
                                    :join?      false
                                    :websockets {"/path" websocket-handler}}]]
      (is server)
      (let [resp-promise (promise)]
        (with-open [socket (ws/connect "ws://localhost:50524/path/"
                                       :on-receive #(deliver resp-promise %))]
          (ws/send-msg socket "hello")
          (is (= "hello" (deref resp-promise 20000 false)))))))
