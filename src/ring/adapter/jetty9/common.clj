(ns ring.adapter.jetty9.common
  (:require [clojure.string :as string])
  (:import [jakarta.servlet.http HttpServletRequest HttpServletResponse]
           (java.io File)
           (java.nio.file FileSystems Paths StandardWatchEventKinds WatchEvent)
           [java.util Locale]
           (java.util.concurrent TimeUnit)))

(defprotocol RequestMapDecoder
  (build-request-map [r]))

(defn set-headers
  "Update a HttpServletResponse with a map of headers."
  [^HttpServletResponse response, headers]
  (doseq [[key val-or-vals] headers]
    (if (string? val-or-vals)
      (.setHeader response key val-or-vals)
      (doseq [val val-or-vals]
        (.addHeader response key val))))
  ; Some headers must be set through specific methods
  (some->> (get headers "Content-Type")
           (.setContentType response)))

(defn- header-kv*
  [^HttpServletRequest req ^String header-name]
  [(.toLowerCase header-name Locale/ENGLISH)
   (->> (.getHeaders req header-name)
        enumeration-seq
        (string/join ","))])

(defn get-headers
  "Creates a name/value map of all the request headers."
  [^HttpServletRequest request]
  (->> (.getHeaderNames request)
       enumeration-seq
       (into {} (map (partial header-kv* request)))))
(defonce noop (constantly nil))

(defn normalize-response
  "Normalize response for ring spec"
  [response]
  (if (string? response)
    {:body response}
    response))

(defn websocket-upgrade-response?
  [{:keys [^long status ws]}]
  ;; NOTE: we know that when :ws attr is provided in the response, we
  ;; need to upgrade to websockets protocol.
  (and (== 101 status) ws))

