(ns ring.adapter.jetty9.common
  (:require [ring.core.protocols :as protocols])
  (:import [org.eclipse.jetty.http HttpHeader HttpField MimeTypes]
           [org.eclipse.jetty.server Request Response SecureRequestCustomizer]
           [org.eclipse.jetty.io Content$Sink]
           [org.eclipse.jetty.http ImmutableHttpFields HttpFields$Mutable HttpURI]
           [java.util Locale]))

(defn set-headers!
  "Update response with a map of headers."
  [^Response response headers]
  (let [^HttpFields$Mutable header-writer (.getHeaders response)]
    (doseq [[key val-or-vals] headers]
      (if (string? val-or-vals)
        (.add header-writer ^String key ^String val-or-vals)
        (doseq [val val-or-vals]
          (.add header-writer ^String key ^String val))))))

(defn- header-kv*
  [^HttpField header]
  [(.getLowerCaseName header) (.getValue header)])

(defn get-headers
  "Creates a name/value map of all the request headers."
  [^Request request]
  (into {} (map header-kv*) (.getHeaders request)))

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

(defn get-charset [^Request request]
  (when-let [content-type (.. request getHeaders (get HttpHeader/CONTENT_TYPE))]
    (if-let [mime-type-charset (.get MimeTypes/CACHE content-type)]
      (str mime-type-charset)
      (MimeTypes/getCharsetFromContentType content-type))))

(defn- get-client-cert [^Request request]
  (.getAttribute request SecureRequestCustomizer/PEER_CERTIFICATES_ATTRIBUTE))

(defn build-request-map
  "Create the request map from the Request object."
  [^Request request]
  (let [^HttpURI uri                 (.getHttpURI request)
        ^ImmutableHttpFields headers (.getHeaders request)]
    {:server-port    (Request/getServerPort request)
     :server-name    (Request/getServerName request)
     :remote-addr    (Request/getRemoteAddr request)
     :uri            (when uri (.getPath uri))
     :query-string   (when uri (.getQuery uri))
     :scheme         (when uri (keyword (.getScheme uri)))
     :request-method (keyword (.toLowerCase ^String (.getMethod request) Locale/ENGLISH))
     :protocol       (.getProtocol (.getConnectionMetaData request))
     :headers        (get-headers request)

     :content-type   (.. headers (get HttpHeader/CONTENT_TYPE))
     :content-length (when-let [l (.. headers (get HttpHeader/CONTENT_LENGTH))]
                       (Integer/valueOf l))

     :character-encoding (get-charset request)
     :ssl-client-cert    (get-client-cert request)
     :body               (Request/asInputStream request)}))

(defn update-response
  "Update Jetty Response from given Ring response map"
  [^Response response response-map]
  (let [{:keys [status headers body]} response-map]
    (cond
      (nil? response)     (throw (NullPointerException. "Response is nil"))
      (nil? response-map) (throw (NullPointerException. "Ring response map is nil"))
      :else
      (do
        (some->> status (.setStatus response))
        (set-headers! response headers)
        (->>
          (Content$Sink/asOutputStream response)
          (protocols/write-body-to-stream body response-map))))))
