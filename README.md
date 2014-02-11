# ring-jetty9-adapter

Ring adapter for Jetty 9 with WebSocket support which means you can use WebSocket in your Clojure application without pain.

## Usage

### Leiningen

`[info.sunng/ring-jetty9-adapter "0.5.0"]`

### Code
```clojure
(use 'ring.adapter.jetty9)
(run-jetty app {:port 50505}) ;; same as the 'official' adapter of jetty 7
```

### WebSocket

From 0.5.0, you don't need `gen-class` to use websocket, thanks to
[NoamB](https://github.com/NoamB).

```clojure
(def ws-handler {:create-fn (fn [ring-req])
                 :connect-fn (fn [ring-req ws-conn ring-session])
                 :error-fn (fn [ring-req ring-session e])
                 :close-fn (fn [ring-req ring-session status reason])
                 :text-fn (fn [ring-req ws-session ring-session text-message])
                 :binary-fn (fn [ring-req ws-session ring-session payload offset len])})
```

There is a new option `:websockets` available. Accepting a map of context path and listener class:
```clojure
(use 'ring.adapter.jetty9)
(run-jetty app {:websockets {"/loc" ws-handler}})
```

In the javascript:
```javascript
// remember to add the trailing slash.
// Otherwise, jetty will return a 302 on websocket upgrade request,
// which is not supported by most browsers.
var ws = new WebSocket("ws://somehost/loc/");
ws.onopen = ....
```

## License

Copyright © 2013 Sun Ning

Distributed under the Eclipse Public License, the same as Clojure.
