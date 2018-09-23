# ring-jetty9-adapter (rj9a)

Ring adapter for Jetty 9 with HTTP2 and WebSocket support.

This is a simple and plain wrapper on Jetty 9. It doesn't introduce
additional thread model or anything else (no unofficial ring variance,
no core.async). You are free to add those on top of our base API.

As of Ring 1.6, the official Jetty adapter has been updated to Jetty
9.2. However, rj9a tracks most recent Jetty release and offers
additional features like http/2 and websocket.

From 0.12, we ship [conscrypt](https://conscrypt.org) TLS
implementation by default, which offers better performance and
compatibility. (Note that conscrypt only works on OpenJDK at the moment)

## Usage

### Leiningen

[![latest version on clojars](http://clojars.org/info.sunng/ring-jetty9-adapter/latest-version.svg)](https://clojars.org/info.sunng/ring-jetty9-adapter)

### Code

In the REPL:

```clojure
(require '[ring.adapter.jetty9 :refer [run-jetty]])
(run-jetty app {:port 50505}) ;; same as the 'official' adapter
```

In ns declaration:

```clojure
(ns my.server
  (:require [ring.adapter.jetty9 :refer [run-jetty]]))
```

### Ring 1.6 async handler

```clojure
(require '[ring.adapter.jetty9 :refer [run-jetty]])

(defn app [request send-response raise-error]
  (send-response {:body "It works!"}))
(run-jetty app {:port 50505 :async? true})
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
* (send! ws msg callback)
* (close! ws)
* (remote-addr ws)
* (idle-timeout! ws timeout)

Notice that we support different type of msg:

* **byte[]** and **ByteBuffer**: send binary websocket message
* **String** and other Object: send text websocket message
* **(fn [ws])** (clojure function): Custom function you can operate on
  Jetty's [RemoteEndpoint](http://download.eclipse.org/jetty/stable-9/apidocs/org/eclipse/jetty/websocket/api/RemoteEndpoint.html)

A callback can also be specified for `send!`:

```clojure
(send! ws msg {:write-failed (fn [throwable]) :write-success (fn [])})
```

 A callback is a map where keys `:write-failed` and `:write-success` are optional.

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

If you want to omit the trailing slash from your URLs (and not receive a redirect from Jetty), you can start the server like:
```clojure
(run-jetty app {:websockets {"/loc" ws-handler}
                :allow-null-path-info true})
```

## Examples

You can find examples in `examples` folder. To run example:

* http: `lein with-profile default,example-http run` a very basic
  example of ring handler
* async: `lein with-profile default,example-async run` ring 1.6 async
  handler example
* http2: `lein with-profile default,example-http2-openjdk run` (NOTE that your
  will need OpenJDK to run this example)
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

Copyright © 2013-2017 Sun Ning

Distributed under the Eclipse Public License, the same as Clojure.
