(ns ring.adapter.jetty9-test
  (:use [clojure.test])
  (:use [ring.adapter.jetty9]))

(defn dummy-app [req]
  {:status 200})

(def websocket-handler
  {:on-connect (fn [ws])
   :on-close (fn [ws])
   :on-error (fn [ws e])
   :on-text (fn [ws msg])
   :on-byte (fn [ws bytes offset length])})

(deftest jetty9-test
  (is (run-jetty dummy-app {:port 50524
                            :join? false
                            :websockets {"/path" websocket-handler}})))
