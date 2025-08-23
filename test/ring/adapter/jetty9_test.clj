(ns ring.adapter.jetty9-test
  (:require [clojure.test :refer :all]
            [ring.adapter.jetty9 :as jetty9]
            [ring.websocket.protocols :as ringws]
            [clj-http.client :as client]
            [less.awful.ssl :as less-ssl]
            #_[gniazdo.core :as ws])
  (:import
   [org.eclipse.jetty.http2.server AbstractHTTP2ServerConnectionFactory]
   [org.eclipse.jetty.http2.frames Frame SettingsFrame]
   [org.eclipse.jetty.http2 WindowRateControl$Factory]
   [org.eclipse.jetty.http2 BufferingFlowControlStrategy FlowControlStrategy$Factory]
   [org.eclipse.jetty.http3.server AbstractHTTP3ServerConnectionFactory]
   [org.eclipse.jetty.quic.quiche.server QuicheServerConnector]))

(defn is-localhost? [addr]
  (or (= addr "127.0.0.1")
      (= addr "localhost")))

(defn test-app-maker [& {:keys [server-port server-name remote-addr
                                uri scheme request-method protocol
                                headers content-type content-length
                                body query-string]
                         :or {server-port 50524
                              uri "/"
                              scheme :http
                              request-method :get
                              protocol "HTTP/1.1"}
                         :as expected-req}]
  (fn [req]
    (is (= server-port (:server-port req)))
    (if (some? server-name)
      (is (= server-name (:server-name req)))
      (is (is-localhost? (:server-name req))))
    (if (some? remote-addr)
      (is (= remote-addr (:remote-addr req)))
      (is (is-localhost? (:remote-addr req))))
    (is (= uri (:uri req)))
    (is (= query-string (:query-string req)))
    (is (= scheme (:scheme req)))
    (is (= request-method (:request-method req)))
    (is (= protocol (:protocol req)))
    #_(is (= (count headers) (count (:headers req))))
    (is (= content-type (:content-type req)))
    (is (= content-length (:content-length req)))
    {:status 200
     :body "yes"}))

(defn dummy-app [req]
  {:status 200 :body "yes"})

(def websocket-handler
  (reify ringws/Listener
    (on-open [this socket])
    (on-close [this socket status reason])
    (on-error [this socket error])
    (on-message [this socket msg]
      (ringws/-send socket msg))))

(defmacro with-jetty
  [[sym [handler opts]] & body]
  `(let [~sym (->> (assoc ~opts :lifecycle-start (partial println "JETTY START")
                          :lifecycle-end   (partial println "JETTY END"))
                   (jetty9/run-jetty ~handler))]
     (try ~@body
          (finally (jetty9/stop-server ~sym)))))

(deftest jetty9-test
  (with-jetty [server [(test-app-maker {:query-string "q=jetty"})
                       {:port 50524
                        :join? false}]]
    (is server)
    (let [resp (client/get "http://localhost:50524/?q=jetty")]
      (is (= 200 (:status resp)))
      (is (= "yes" (:body resp))))))

(deftest jetty9-post-test
  (with-jetty [server [(test-app-maker {:request-method :post
                                        :content-type "text/plain"
                                        :content-length 6})
                       {:port 50524
                        :join? false}]]
    (is server)
    (let [resp (client/post "http://localhost:50524/"
                            {:body "tomcat"
                             :content-type "text/plain"})]
      (is (= 200 (:status resp)))
      (is (= "yes" (:body resp))))))

(deftest wrap-jetty-handler-test
  (let [counter (atom 0)]
    (with-jetty [server [(test-app-maker {:request-method :post
                                          :content-type "text/plain"
                                          :content-length 6})
                         {:port 50524
                          :join? false
                          :wrap-jetty-handler (fn [handler]
                                                (proxy [org.eclipse.jetty.server.Handler$Abstract] []
                                                  (handle [req resp cb]
                                                    (swap! counter inc)
                                                    (.handle handler req resp cb))))}]]
      (is server)
      (let [resp (client/post "http://localhost:50524/"
                              {:body "tomcat"
                               :content-type "text/plain"})]
        (is (= 200 (:status resp)))
        (is (= "yes" (:body resp)))
        (is (= 1 @counter))))))

#_(deftest jetty9-websocket-test
    (with-jetty [server [(fn [req]
                           {:ring.websocket/listener websocket-handler})
                         {:port 50524 :join? false}]]
      (is server)
      (let [resp (client/get "http://localhost:50524/"
                             {:headers {"Connection" "Upgrade"
                                        "Upgrade" "websocket"
                                        "Sec-WebSocket-Version" "13"
                                        "Sec-WebSocket-Extensions" "permessage-deflate; client_max_window_bits"
                                        "Sec-WebSocket-Key" "ZxKJ7pcanojJTxHexoMmrA=="}})]
        (is (= 101 (:status resp))))))

(deftest var-handler
  (with-jetty [server [#'dummy-app {:port 50524
                                    :join? false}]]
    (is server)
    (let [resp (client/get "http://localhost:50524/")]
      (is (= 200 (:status resp)))
      (is (= "yes" (:body resp))))))

(defn ssl-context []
  (less-ssl/ssl-context "dev-resources/test/key.pem"
                        "dev-resources/test/cert.pem"
                        "dev-resources/test/cert.pem"))

(deftest keystore-and-truststore-test
  (with-jetty [server [dummy-app {:ssl-port        50524
                                  :http?           false
                                  :ssl             true
                                  :join?           false
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
  (with-jetty [server [(test-app-maker {:scheme :https}) {:ssl-port        50524
                                                          :http?           false
                                                          :ssl?            true
                                                          :join?           false
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

(defn- get-h2-factory-options
  [factory]
  (->> factory
       ((juxt
         #(.isConnectProtocolEnabled %)
         #(.getFlowControlStrategyFactory %)
         #(.getInitialSessionRecvWindow %)
         #(.getInitialStreamRecvWindow %)
         #(.getMaxConcurrentStreams %)
         #(.getMaxDecoderTableCapacity %)
         #(.getMaxFrameSize %)
         #(.getMaxHeaderBlockFragment %)
         #(.getMaxSettingsKeys %)
         #(.getRateControlFactory %)
         #(.getStreamIdleTimeout %)
         #(.isUseInputDirectByteBuffers %)
         #(.isUseOutputDirectByteBuffers %)))
       (zipmap  [:connect-protocol-enabled
                 :flow-control-strategy-factory
                 :initial-session-recv-window
                 :initial-stream-recv-window
                 :max-concurrent-streams
                 :max-dynamic-table-size
                 :max-frame-length
                 :max-header-block-fragment
                 :max-setting-keys
                 :rate-control-factory
                 :stream-idle-timeout
                 :use-input-direct-byte-buffers
                 :use-output-direct-byte-buffers])))

(deftest http2-options-test
  (let [flow-control-strategy-factory
        (proxy [FlowControlStrategy$Factory] []
          (newFlowControlStrategy [] (BufferingFlowControlStrategy. (float 0.7))))

        h2-options
        {:connect-protocol-enabled false
         :flow-control-strategy-factory flow-control-strategy-factory
         :initial-session-recv-window (* 2048 2048)
         :initial-stream-recv-window (* 512 1024)
         :max-concurrent-streams 256
         :max-dynamic-table-size 2048
         :max-frame-length Frame/MAX_MAX_SIZE
         :max-header-block-fragment 1
         :max-setting-keys SettingsFrame/MAX_MAX_SIZE
         :rate-control-factory (WindowRateControl$Factory. 100)
         :stream-idle-timeout 1000
         :use-input-direct-byte-buffers false
         :use-output-direct-byte-buffers false}]
    (with-jetty [server [dummy-app {:ssl-port        50524
                                    :port            50523
                                    :h2?              true
                                    :h2c?             true
                                    :h2-options      h2-options
                                    :ssl?            true
                                    :join?           false
                                    :ssl-context     (ssl-context)}]]
      (let [factories-options
            (->> (.getConnectors server)
                 (mapcat #(.getConnectionFactories %))
                 (filter #(isa? (type %) AbstractHTTP2ServerConnectionFactory))
                 (map get-h2-factory-options))]
        (doseq [fo factories-options]
          (is (= fo h2-options)))))))

(defn- get-quic-options
  [http3-server-connector]
  (->> (.getServerQuicConfiguration http3-server-connector)
       ((juxt
         #(.getSessionMaxData %)
         #(.getLocalBidirectionalStreamMaxData %)
         #(.getRemoteBidirectionalStreamMaxData %)
         #(.getUnidirectionalStreamMaxData %)
         #(.getBidirectionalMaxStreams %)
         #(.getUnidirectionalMaxStreams %)
         #(.getInputBufferSize %)
         #(.getOutputBufferSize %)
         #(.isUseInputDirectByteBuffers %)
         #(.isUseOutputDirectByteBuffers %)
         #(.getStreamIdleTimeout %)
         #(.getMinInputBufferSpace %)))
       (zipmap [:session-max-data
                :local-bidirectional-stream-max-data
                :remote-bidirectional-stream-max-data
                :unidirectional-stream-max-data
                :bidirectional-max-streams
                :unidirectional-max-streams
                :input-buffer-size
                :output-buffer-size
                :use-input-direct-byte-buffers
                :use-output-direct-byte-buffers
                :stream-idle-timeout
                :min-input-buffer-space])))

(deftest http3-quic-options-test
  (let [http3-pem-work-dir "target/pemwork"
        http3-options
        {:input-buffer-size 2048
         :unidirectional-max-streams 0
         :session-max-data 25165824
         :local-bidirectional-stream-max-data 0
         :use-output-direct-byte-buffers true
         :unidirectional-stream-max-data 0
         :min-input-buffer-space 1500
         :use-input-direct-byte-buffers true
         :bidirectional-max-streams 1
         :output-buffer-size 2048
         :remote-bidirectional-stream-max-data 16777216
         :stream-idle-timeout 0}]
    (.mkdirs (clojure.java.io/file http3-pem-work-dir))
    (with-jetty [server [dummy-app {:ssl-port        50524
                                    :port            50523
                                    :ssl?            true
                                    :join?           false
                                    :http3?          true
                                    :http3-options   http3-options
                                    :http3-pem-work-directory http3-pem-work-dir
                                    :keystore        "dev-resources/keystore.jks"
                                    :key-password    "111111"
                                    :keystore-type   "jks"}]]
      (let [quic-options
            (->> (.getConnectors server)
                 (filter #(isa? (type %) QuicheServerConnector))
                 (map get-quic-options))]
        (doseq [qo quic-options]
          (is (= qo http3-options)))))))
