(ns ring.adapter.jetty9.common)

(defprotocol RequestMapDecoder
  (build-request-map [r]))
