(ns ring.adapter.jetty9.websocket-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.adapter.jetty9 :as jetty]
            [ring.adapter.jetty9.websocket :as websocket]
            [ring.websocket.protocols :as ws])
  (:import [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Builder WebSocket$Listener]
           [java.nio ByteBuffer]
           [java.util.concurrent CompletableFuture LinkedBlockingQueue TimeUnit]
           [org.eclipse.jetty.server Server ServerConnector]
           [org.eclipse.jetty.websocket.api Callback Session$Listener]))

(defn- buffer-bytes
  [buffer]
  (let [bytes (byte-array (ByteBuffer/.remaining buffer))]
    (ByteBuffer/.get (ByteBuffer/.duplicate buffer) bytes)
    (vec bytes)))

(deftype MessageListener [receive]
  ws/Listener
  (on-open [_ _socket])
  (on-message [_ socket message] (receive socket message))
  (on-pong [_ _socket _payload])
  (on-error [_ _socket _error])
  (on-close [_ _socket _code _reason]))

(deftype ReceiveCallback [events recycle]
  Callback
  (succeed [_]
    (swap! events conj :succeed)
    (recycle))
  (fail [_ error]
    (swap! events conj [:fail error])))

(deftest binary-callback-completes-and-retained-payload-is-owned
  (doseq [direct [false true]]
    (testing (if direct "direct buffer" "heap buffer")
      (let [storage (if direct (ByteBuffer/allocateDirect 5) (ByteBuffer/allocate 5))
            events (atom [])
            retained (atom nil)
            listener (MessageListener.
                      (fn [_ payload]
                        (is (empty? @events) "delivery precedes callback completion")
                        (reset! retained payload)))
            endpoint (#'websocket/proxy-ws-adapter listener)
            callback (ReceiveCallback. events
                                       #(doseq [i (range 1 4)] (ByteBuffer/.put storage i (byte 0))))]
        (ByteBuffer/.put storage (byte-array [9 1 2 3 8]))
        (ByteBuffer/.position storage 1)
        (ByteBuffer/.limit storage 4)
        (let [payload (ByteBuffer/.asReadOnlyBuffer storage)]
          (Session$Listener/.onWebSocketBinary endpoint payload callback)
          (is (= [:succeed] @events))
          (is (= [1 2 3] (buffer-bytes @retained))
              "the Ring listener can retain bytes after Jetty recycles its buffer")
          (is (= [0 0 0] (buffer-bytes storage)))
          (is (= 1 (ByteBuffer/.position payload)))
          (is (= 4 (ByteBuffer/.limit payload))))))))

(deftest empty-binary-message-completes
  (let [events (atom [])
        received (atom nil)
        endpoint (#'websocket/proxy-ws-adapter
                  (MessageListener. (fn [_ payload] (reset! received (buffer-bytes payload)))))]
    (Session$Listener/.onWebSocketBinary endpoint (ByteBuffer/allocate 0)
                                         (ReceiveCallback. events (constantly nil)))
    (is (= [] @received))
    (is (= [:succeed] @events))))

(deftest listener-failure-completes-the-callback-once
  (doseq [error [(ex-info "Listener failed" {}) (AssertionError. "Listener failed")]]
    (let [events (atom [])
          endpoint (#'websocket/proxy-ws-adapter
                    (MessageListener. (fn [_ _] (throw error))))]
      (Session$Listener/.onWebSocketBinary endpoint (ByteBuffer/wrap (byte-array [1]))
                                           (ReceiveCallback. events (constantly nil)))
      (is (= [[:fail error]] @events)))))

(deftest completion-failure-does-not-complete-the-callback-twice
  (let [events (atom [])
        error (ex-info "Callback failed" {})
        endpoint (#'websocket/proxy-ws-adapter (MessageListener. (fn [_ _] nil)))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (Session$Listener/.onWebSocketBinary endpoint (ByteBuffer/allocate 0)
                                                      (ReceiveCallback. events #(throw error)))))
    (is (= [:succeed] @events))))

(deftype ClientListener [received fragments]
  WebSocket$Listener
  (onOpen [_ socket] (WebSocket/.request socket 1))
  (onBinary [_ socket payload last]
    (let [message (swap! fragments into (buffer-bytes payload))]
      (when last
        (LinkedBlockingQueue/.offer received message)
        (reset! fragments [])))
    (WebSocket/.request socket 1)
    (CompletableFuture/completedFuture nil))
  (onText [_ socket text _last]
    (LinkedBlockingQueue/.offer received (str text))
    (WebSocket/.request socket 1)
    (CompletableFuture/completedFuture nil))
  (onError [_ _socket error] (LinkedBlockingQueue/.offer received error)))

(deftest repeated-and-fragmented-binary-roundtrips
  (doseq [async [false true]]
    (testing (if async "asynchronous Ring handler" "synchronous Ring handler")
      (let [failures (atom [])
            listener (MessageListener.
                      (fn [socket payload]
                        (ws/-send-async socket payload (constantly nil) #(swap! failures conj %))))
            response {:ring.websocket/listener listener}
            handler (if async (fn [_ respond _raise] (respond response)) (constantly response))
            server (jetty/run-jetty handler {:host "127.0.0.1" :port 0 :join? false :async? async})
            port (ServerConnector/.getLocalPort (first (Server/.getConnectors server)))
            client (HttpClient/newHttpClient)
            received (LinkedBlockingQueue.)]
        (try
          (let [socket (CompletableFuture/.get
                        (WebSocket$Builder/.buildAsync (HttpClient/.newWebSocketBuilder client)
                                                       (URI. (str "ws://127.0.0.1:" port "/"))
                                                       (ClientListener. received (atom [])))
                        5 TimeUnit/SECONDS)]
            (try
              (doseq [value [[1 2 3] [] [0 -1 4]]]
                (CompletableFuture/.get
                 (WebSocket/.sendBinary socket (ByteBuffer/wrap (byte-array value)) true)
                 5 TimeUnit/SECONDS)
                (is (= value (LinkedBlockingQueue/.poll received 2 TimeUnit/SECONDS))))
              (CompletableFuture/.get
               (WebSocket/.sendBinary socket (ByteBuffer/wrap (byte-array [5 6])) false)
               5 TimeUnit/SECONDS)
              (CompletableFuture/.get
               (WebSocket/.sendBinary socket (ByteBuffer/wrap (byte-array [7 8])) true)
               5 TimeUnit/SECONDS)
              (is (= [5 6 7 8] (LinkedBlockingQueue/.poll received 2 TimeUnit/SECONDS)))
              (CompletableFuture/.get (WebSocket/.sendText socket "after binary: λ" true)
                                      5 TimeUnit/SECONDS)
              (is (= "after binary: λ" (LinkedBlockingQueue/.poll received 2 TimeUnit/SECONDS)))
              (is (empty? @failures))
              (finally (WebSocket/.abort socket))))
          (finally
            (Server/.stop server)))))))
