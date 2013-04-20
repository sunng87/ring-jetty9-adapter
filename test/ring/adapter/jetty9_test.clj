(ns ring.adapter.jetty9-test
  (:use [clojure.test])
  (:use [ring.adapter.jetty9]))

(defn dummy-app [req]
  {:status 200})

(deftest jetty9-test
  (is (run-jetty dummy-app {:port 50524
                            :join? false})))

