# ring-jetty9-adapter

Ring adapter for Jetty 9 with WebSocket support which means you can use WebSocket in your Clojure application without pain.

## Usage

### Leiningen

`[info.sunng/ring-jetty9-adapter "0.6.0"]`

### Code
```clojure
(use 'ring.adapter.jetty9)
(run-jetty app {:port 50505}) ;; same as the 'official' adapter of jetty 7
```

### WebSocket

You can define following handlers for websocket events.

```clojure
(def ws-handler {:on-connect (fn [ws])
                 :on-error (fn [ws e])
                 :on-close (fn [ws])
                 :on-text (fn [ws text-message])
                 :on-bytes (fn [ws bytes offset len])})
```

WebSocketProtocol allows you to read and write data on the `ws` value:

* (send-text ws ^String text)
* (send-bytes ws ^ByteBuffer bytes)
* (close ws)
* (remote-addr ws)
* (idle-timeout ws timeout)

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
