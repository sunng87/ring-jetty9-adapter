(ns ring.adapter.jetty9.servlet
  (:require [clojure.string :as string]
            [ring.adapter.jetty9.common :as common]
            [ring.core.protocols :as protocols])
  (:import [jakarta.servlet AsyncContext]
           [jakarta.servlet.http HttpServletRequest HttpServletResponse]
           [java.util Locale]))

(defn- get-content-length
  "Returns the content length, or nil if there is no content."
  [^HttpServletRequest request]
  (let [length (.getContentLength request)]
    (if (>= length 0) length)))

(defn- get-client-cert
  "Returns the SSL client certificate of the request, if one exists."
  [^HttpServletRequest request]
  (first (.getAttribute request "jakarta.servlet.request.X509Certificate")))

(defn build-request-map
  "Create the request map from the HttpServletRequest object."
  [^HttpServletRequest request]
  {:server-port        (.getServerPort request)
   :server-name        (.getServerName request)
   :remote-addr        (.getRemoteAddr request)
   :uri                (.getRequestURI request)
   :query-string       (.getQueryString request)
   :scheme             (keyword (.getScheme request))
   :request-method     (keyword (.toLowerCase (.getMethod request) Locale/ENGLISH))
   :protocol           (.getProtocol request)
   :headers            (common/get-headers request)
   :content-type       (.getContentType request)
   :content-length     (get-content-length request)
   :character-encoding (.getCharacterEncoding request)
   :ssl-client-cert    (get-client-cert request)
   :body               (.getInputStream request)})

(defn- make-output-stream
  [^HttpServletResponse response ^AsyncContext context]
  (let [os (.getOutputStream response)]
    (if (nil? context)
      os
      (proxy [java.io.FilterOutputStream] [os]
        (write
          ([b]         (.write os ^bytes b))
          ([b off len] (.write os b off len)))
        (close []
          (.close os)
          (.complete context))))))

(defn update-servlet-response
  "Update the HttpServletResponse using a response map. Takes an optional
  AsyncContext."
  ([response response-map]
   (update-servlet-response response nil response-map))
  ([^HttpServletResponse response context response-map]
   (let [{:keys [status headers body]} response-map]
     (cond
       (nil? response)     (throw (NullPointerException. "HttpServletResponse is nil"))
       (nil? response-map) (throw (NullPointerException. "Response map is nil"))
       :else
       (do
         (some->> status (.setStatus response))
         (common/set-headers response headers)
         (->> (make-output-stream response context)
              (protocols/write-body-to-stream body response-map)))))))
