(ns ring.adapter.jetty9.common
  (:require [clojure.string :as string])
  (:import [org.eclipse.jetty.http HttpHeader HttpField MimeTypes]
           [org.eclipse.jetty.io ContentSourceInputStream]
           [org.eclipse.jetty.server Request Response]

           [java.util Locale]))

(defprotocol RequestMapDecoder
  (build-request-map [r]))

(defn set-headers
  "Update a HttpServletResponse with a map of headers."
  [^Response response, headers]
  (doseq [[key val-or-vals] headers]
    (if (string? val-or-vals)
      (.setHeader response key val-or-vals)
      (doseq [val val-or-vals]
        (.addHeader response key val))))
  ; Some headers must be set through specific methods
  (some->> (get headers "Content-Type")
           (.setContentType response)))

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
  "Create the request map from the HttpServletRequest object."
  [^Request request]
  {:server-port        (Request/getLocalPort request)
   :server-name        (Request/getLocalAddr request)
   :remote-addr        (Request/getRemoteAddr request)
   :uri                (when-let 3[uri (.getHttpURI request)]
                         (.getPath uri))
   :query-string       (when-let [uri (.getHttpURI request)]
                         (.getQuery uri))
   :scheme             (when-let [uri (.getHttpURI request)]
                         (keyword (.getScheme uri)))
   :request-method     (keyword (.toLowerCase (.getMethod request) Locale/ENGLISH))
   :protocol           (.getProtocol (.getConnectionMetaData request))
   :headers            (common/get-headers request)
   :content-type       (.. request getHeaders (get HttpHeader/CONTENT_TYPE))
   :content-length     (.. request getHeaders (get HttpHeader/CONTENT_LENGTH))
   :character-encoding (get-charset request)
   :ssl-client-cert    (get-client-cert request)
   :body               (ContentSourceInputStream request)})
