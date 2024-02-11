(ns rj9a.http
  (:gen-class)
  (:require [ring.adapter.jetty9 :as jetty]
            [clojure.java.io :as io]))

(defn dummy-app [req]
  (println req)
  {:body "It works" :status 200})

(comment
  (defn dummy-app [req]
    {:status 200
     :body (reify
             ring.core.protocols/StreamableResponseBody
             (write-body-to-stream [_ _ output-stream]
               (let [writer (io/writer output-stream)]
                 (future (loop [i 10]
                           (doto writer (.write (str "data: " i "\n")) .flush)
                           (if (zero? i)
                             (.close writer)
                             (do (Thread/sleep 100)
                                 (recur (dec i)))))))))}))

(defn -main [& args]
  (jetty/run-jetty dummy-app {:port 5000}))
