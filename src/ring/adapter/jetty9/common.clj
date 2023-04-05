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
  (and status (== 101 status) ws))

(defn on-file-change!
  "Sets up a WatchService, and registers the parent of <target> with it for changes.
   A separate thread constantly polls for events, and when the affected file matches
   <target>, calls <on-change> (no-args). Returns a (cancellable) Future.
   ATTENTION: Cancelling the future doesn't break the loop immediately - might take
   up to 5 minutes (worst-case)!"
  [^File target on-change!]
  {:pre [(.exists target)]}
  (let [watch-service   (-> (FileSystems/getDefault) .newWatchService)
        target-absolute (.getAbsoluteFile target)
        target-path     (-> (.toPath target-absolute) .getFileName)]
    ;; register the parent directory with the watch-service
    (-> target-absolute
        .getParent
        (Paths/get (into-array String []))
        (.register watch-service (into-array [StandardWatchEventKinds/ENTRY_MODIFY])))
    ;; start event-polling thread
    (future
      (while (not (.isInterrupted (Thread/currentThread)))
        (when-some [wk (.poll watch-service 5 TimeUnit/MINUTES)] ;; blocking call
          (run!
            (fn [^WatchEvent e]
              (let [affected-path (.context e)]
                (when (= affected-path target-path)
                  ;; only interested in changes in
                  ;; one file (renaming NOT included)
                  (on-change! target))))
            (.pollEvents wk))
          (.reset wk)))
      (.close watch-service))))
