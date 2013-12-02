# ring-jetty9-adapter

Ring adapter for Jetty 9 with WebSocket support which means you can use WebSocket in your Clojure application without pain.

## Usage

### Leiningen

`[info.sunng/ring-jetty9-adapter "0.3.0"]`

### Code
```clojure
(use 'ring.adapter.jetty9)
(run-jetty app {:port 50505}) ;; same as the 'official' adapter of jetty 7
```

### WebSocket

Use clojure's `gen-class` to create a websocket listener class:

```clojure
;; sample code
(ns xxx.ws.location
  (:gen-class
   :name xxx.LocationTracker
   :init init
   :state state
   :extends org.eclipse.jetty.websocket.api.WebSocketAdapter
   :prefix ws-
   :exposes-methods {onWebSocketConnect superOnWebSocketConnect})
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as logging])
  (:import (org.eclipse.jetty.websocket.api WebSocketAdapter)
           (java.util UUID)))

(defn ws-init []
  [[] {:client-id (str (UUID/randomUUID))}])

(defn ws-onWebSocketConnect [this session]
  (.superOnWebSocketConnect this session)
  (logging/warn "new connection: " (get-client-id this))

(defn ws-onWebSocketText [this message]
  (let [msg (json/read-json message)]
    (case (:type msg)
      ...)))

(defn ws-onWebSocketClose [this status reason]
  (logging/debug "close socket"))
```

There is a new option `:websockets` available. Accepting a map of context path and listener class:
```clojure
(use 'ring.adapter.jetty9)
(run-jetty app {:websockets {"/loc" LocationTracker}})
```

In the javascript:
```javascript
// remember to add tailing slash.
// Otherwise, jetty will return a 302 on websocket upgrade request,
// which is not supported by most browsers.
var ws = new WebSocket("ws://somehost/loc/");
ws.onopen = ....
```

## License

Copyright © 2013 Sun Ning

Distributed under the Eclipse Public License, the same as Clojure.
