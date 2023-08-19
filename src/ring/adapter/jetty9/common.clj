(ns ring.adapter.jetty9.common
  (:require [clojure.string :as string]
            [ring.core.protocols :as protocols])
  (:import [org.eclipse.jetty.http HttpHeader HttpField MimeTypes]
           [org.eclipse.jetty.io.content
            ContentSourceInputStream ContentSinkOutputStream]
           [org.eclipse.jetty.server Request Response]

           [java.util Locale]))

(defprotocol RequestMapDecoder
  (build-request-map [r]))

(defn set-headers!
  "Update response with a map of headers."
  [^Response response headers]
  (let [header-writer (.getHeaders response)]
    (doseq [[key val-or-vals] headers]
      (if (string? val-or-vals)
        (.add header-writer key val-or-vals)
        (doseq [val val-or-vals]
          (.add header-writer key val))))))

(defn- header-kv*
  [^HttpField header]
  [(.getLowerCaseName header) (.getValue header)])

(defn get-headers
  "Creates a name/value map of all the request headers."
  [^Request request]
  (let [headers (.getHeaders request)]
    (into {} (map header-kv* headers))))

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
  ;; TODO: get data from SecureRequestWithTLSData
  )


(defn build-request-map
  "Create the request map from the Request object."
  [^Request request]
  {:server-port        (Request/getLocalPort request)
   :server-name        (Request/getLocalAddr request)
   :remote-addr        (Request/getRemoteAddr request)
   :uri                (when-let [uri (.getHttpURI request)]
                         (.getPath uri))
   :query-string       (when-let [uri (.getHttpURI request)]
                         (.getQuery uri))
   :scheme             (when-let [uri (.getHttpURI request)]
                         (keyword (.getScheme uri)))
   :request-method     (keyword (.toLowerCase (.getMethod request) Locale/ENGLISH))
   :protocol           (.getProtocol (.getConnectionMetaData request))
   :headers            (get-headers request)
   :content-type       (.. request getHeaders (get HttpHeader/CONTENT_TYPE))
   :content-length     (.. request getHeaders (get HttpHeader/CONTENT_LENGTH))
   :character-encoding (get-charset request)
   :ssl-client-cert    (get-client-cert request)
   :body               (ContentSourceInputStream. request)})

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
        (->> (ContentSinkOutputStream. response)
             (protocols/write-body-to-stream body response-map))))))
