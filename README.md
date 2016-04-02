# ring-jetty9-adapter (rj9a)

Ring adapter for Jetty 9 with HTTP2 and WebSocket support.

This is a simple and plain wrapper on Jetty 9. It doesn't introduce
additional thread model or anything else (no unofficial ring variance,
no core.async). You are free to add those on top of our base API.

## Usage

### Leiningen

[![latest version on clojars](http://clojars.org/info.sunng/ring-jetty9-adapter/latest-version.svg)](https://clojars.org/info.sunng/ring-jetty9-adapter)

### Code

In the REPL:

```clojure
(require '[ring.adapter.jetty9 :refer [run-jetty]])
(run-jetty app {:port 50505}) ;; same as the 'official' adapter of jetty 7
```

In ns declaration:

```clojure
(ns my.server
  (:require [ring.adapter.jetty9 :refer [run-jetty]]))
```

### WebSocket

You can define following handlers for websocket events.

```clojure
(def ws-handler {:on-connect (fn [ws])
                 :on-error (fn [ws e])
                 :on-close (fn [ws status-code reason])
                 :on-text (fn [ws text-message])
                 :on-bytes (fn [ws bytes offset len])})
```

WebSocketProtocol allows you to read and write data on the `ws` value:

* (send! ws msg)
* (close! ws)
* (remote-addr ws)
* (idle-timeout! ws timeout)

Notice that we support different type of msg:

* **byte[]** and **ByteBuffer**: send binary websocket message
* **String** and other Object: send text websocket message
* **(fn [ws])** (clojure function): Custom function you can operate on
  Jetty's [RemoteEndpoint](http://download.eclipse.org/jetty/stable-9/apidocs/org/eclipse/jetty/websocket/api/RemoteEndpoint.html)

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

## Examples

You can find examples in `examples` folder. To run example:

* http2: `lein with-profile default,example-http2 run` (NOTE that your
  will need JDK8 to run this example)
* websocket: `lein with-profile default,example-websocket run`

## Contributors

* [kristinarodgers](https://github.com/kristinarodgers)
* [xtang](https://github.com/xtang)
* [NoamB](https://github.com/NoamB)
* [mpenet](https://github.com/mpenet)
* [aesterline](https://github.com/aesterline)
* [trptcolin](https://github.com/trptcolin)
* [paomian](https://github.com/paomian)

## License

Copyright © 2013-2016 Sun Ning

Distributed under the Eclipse Public License, the same as Clojure.
