(ns ring.adapter.jetty9.handlers.async
  (:require
   [ring.adapter.jetty9.common :as common]
   [ring.adapter.jetty9.websocket :as ws])
  (:import [org.eclipse.jetty.server Request Response]
           [org.eclipse.jetty.util Callback]
           [org.eclipse.jetty.util.thread Scheduler$Task]
           [java.util.concurrent TimeUnit TimeoutException]
           [java.util.concurrent.atomic AtomicBoolean])
  (:gen-class
   :name ring.adapter.jetty9.handlers.AsyncProxyHandler
   :extends org.eclipse.jetty.server.Handler$Abstract$NonBlocking
   :state state
   :init init
   :constructors {[clojure.lang.IFn
                   clojure.lang.IPersistentMap] []}
   :prefix "-"))

(defn -init
  [ring-handler opts]
  [[] [ring-handler opts]])

(defn -handle
  "Asynchronous override for `Handler$Abstract$NonBlocking/handle"
  [^ring.adapter.jetty9.handlers.AsyncProxyHandler this
   ^Request request
   ^Response response
   ^Callback callback]
  (let [[handler {:keys [async-timeout async-timeout-handler]
                  :or {async-timeout 0}
                  :as options}] (.state this)
        scheduler (.. request getConnectionMetaData getConnector getScheduler)
        ;; Ensures only one of respond / raise / timeout completes the
        ;; callback, since Jetty's `Callback` may be completed exactly once.
        won (AtomicBoolean. false)
        ;; Holds the scheduled timeout task so it can be cancelled when the
        ;; handler completes normally before the deadline.
        timeout-task (atom nil)
        ;; Complete the callback. These are the "raw" callbacks handed to
        ;; the timeout-handler (which has already won the race by the time
        ;; it runs), so they do not re-check `won`.
        complete-response (fn [response-map]
                            (let [response-map (common/normalize-response response-map)]
                              (if (common/websocket-upgrade-response? response-map)
                                (ws/upgrade-websocket request response callback response-map options)
                                (common/update-response request response response-map)))
                            (.succeeded callback))
        complete-error (fn [^Throwable exception]
                         (.failed callback exception))
        cancel-timeout (fn []
                         (when-some [^Scheduler$Task task @timeout-task]
                           (.cancel task)))
        ;; Guarded callbacks given to the main handler: claim the slot,
        ;; cancel the pending timeout, then complete.
        respond (fn [response-map]
                  (when (.compareAndSet won false true)
                    (cancel-timeout)
                    (complete-response response-map)))
        raise (fn [^Throwable exception]
                (when (.compareAndSet won false true)
                  (cancel-timeout)
                  (complete-error exception)))]
    ;; Schedule a timeout when one is requested. On expiry we claim the
    ;; slot; if we win, either invoke the user-supplied
    ;; `async-timeout-handler` (with the raw complete callbacks) or, by
    ;; default, fail the callback with a `TimeoutException`.
    (when (pos? async-timeout)
      (reset! timeout-task
              (.schedule scheduler
                         ^Runnable
                         (fn []
                           (when (.compareAndSet won false true)
                             (if async-timeout-handler
                               (async-timeout-handler
                                (common/build-request-map request)
                                complete-response
                                complete-error)
                               (complete-error (TimeoutException.
                                                (str "Async request timed out after "
                                                     async-timeout "ms"))))))
                         (long async-timeout)
                         TimeUnit/MILLISECONDS)))
    ;; Dispatch the async handler on the server's thread pool (mirrors
    ;; Jetty's `AsyncContext.start` / `Context.execute` semantics) so that
    ;; we never block the connector dispatch thread, which is especially
    ;; important for HTTP/2 and HTTP/3 where a single thread multiplexes
    ;; many streams.
    (.execute (.getContext request)
              ^Runnable
              (fn []
                (handler (common/build-request-map request) respond raise)))
    true))
