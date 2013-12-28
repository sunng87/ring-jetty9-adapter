(ns ring.adapter.jetty9-test
  (:use [clojure.test])
  (:use [ring.adapter.jetty9]))

(defn dummy-app [req]
  {:status 200})

(deftest jetty9-test
  (is (run-jetty dummy-app {:port 50524
                            :join? false
                            :websockets {"/path" {:connect-fn #(prn "connect-fn" % %2)
                                                  :text-fn    #(prn "text-fn" % %2)
                                                  :binary-fn  #(prn "binary-fn" % %2 %3 %4)
                                                  :close-fn   #(prn "close-fn" % %2 %3)
                                                  :error-fn   #(prn "error-fn" % %2)}}})))

