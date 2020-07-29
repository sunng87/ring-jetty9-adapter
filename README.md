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
compatibility. If conscrypt is not available in your platform, you can
still fallback to JDK implementation by excluding conscrypt
dependencies and including OpenJDK8 or JDK9 dependencies. See example
profiles in `project.clj` for detail.

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

### Use HTTP 1.1 Only

If you use plain socket http 1.1 only, for example, behind an nginx
with ssl off-loading, you can exclude HTTPs dependencies to reduce the
uberjar size:

```clojure
:exclusions [org.eclipse.jetty/jetty-alpn-conscrypt-server
             org.conscrypt/conscrypt-openjdk-uber]
```

### HTTP/2

To enable HTTP/2 on cleartext and secure transport, you can simply add
options to `run-jetty` like:

```clojure
(jetty/run-jetty dummy-app {:port 5000
                            :h2c? true  ;; enable cleartext http/2
                            :h2? true   ;; enable http/2
                            :ssl? true  ;; ssl is required for http/2
                            :ssl-port 5443
                            :keystore "dev-resources/keystore.jks"
                            :key-password "111111"
                            :keystore-type "jks"})
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

### Websocket Handshake

Sometimes you may have a negotiation with the websocket client on the
handshake (upgrade request) phase. You can define a ring like function
that returns the websocket handler, or raises an error. You may also
select a subprotocol from `(:subprotocol request)` and configure
available `(:extensions request)`. See [websocket
example](https://github.com/sunng87/ring-jetty9-adapter/blob/master/examples/rj9a/websocket.clj)
for detail.

## Examples

You can find examples in `examples` folder. To run example:

* http: `lein with-profile default,example-http run` a very basic
  example of ring handler
* async: `lein with-profile default,example-async run` ring 1.6 async
  handler example
* http2 with openjdk 8u252 and above: `lein with-profile example-http2-openjdk8 run`
* http2 with openjdk 11+ `lein with-profile example-http2 run`
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
