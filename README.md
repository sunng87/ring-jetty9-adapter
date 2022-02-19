# ring-jetty9-adapter (rj9a)

Ring adapter for Jetty 10 and above versions, with HTTP/3, WebSocket and
Expiremental HTTP/3 support.

This is a simple and plain wrapper on Jetty 9. It doesn't introduce
additional thread model or anything else (no unofficial ring variance,
no core.async). You are free to add those on top of our base API.

As of Ring 1.6, the official Jetty adapter has been updated to Jetty
9.2. However, rj9a tracks most recent Jetty release and offers
additional features like http/2 and websocket.

Jetty 10 and 11 were released with JDK 8 support dropped. The 0.15+ will use
Jetty 10 by default. To use JDK 8 and Jetty 9, check the `jetty-9` branch and
`0.14` releases.

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

### HTTP/2

ALPN is required for HTTP/2 transport, For rj9a versions before
`0.17`, you will need additional dependency to enable ALPN. Add
following dependencies according to the jdk version you use.

* For JDK 11 and above, add `[org.eclipse.jetty/jetty-alpn-java-server ~jetty-version]`
* For OpenJDK 8u252 and above, add `[org.eclipse.jetty/jetty-alpn-openjdk8-server ~jetty-version]`
* For OpenJDK 8 but below 252, please check `example-http2-legacy` profile
in project.clj for boot-classpath configuration
* For any version of JDK users, conscrypt implementation is supported by
adding `[org.eclipse.jetty/jetty-alpn-conscrypt-server ~jetty-version]` but it's not recommended for
now because of [memory leak issue](https://github.com/google/conscrypt/issues/835)

Note your will need to replace `~jetty-version` with corresponding jetty version that
your version of rj9a uses.

For rj9a version `0.17.1` and newer,
`org.eclipse.jetty/jetty-alpn-java-server` is included by default.

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

### HTTP/3

From 10.0.8, Jetty ships an expiremental HTTP/3 implementation based
on [the quiche library](https://github.com/cloudflare/quiche). rj9a
made it an optional feature. To enable HTTP/3 support, you will need
to:

* Install libquiche 0.11.0 on your system and make sure `libquiche.so`
  can be loaded from the Clojure(Java) application.
* In addition to rj9a, add dependency
  `[info.sunng/ring-jetty9-adapter-http3 "0.1.0"]` to your clojure
  project to bring in HTTP/3 staff.
* Provide certficate and key just like HTTPs setup because HTTP/3 is
  secure by default. There is no plaintext fallback for now.
* Provide option `:http3? true` to `run-jetty` to enable HTTP/3
  protocol.

```clojure
(jetty/run-jetty dummy-app {:port 5000  ;; default clear-text http/1.1 port
                            :http3 true  ;; enable http/3 support
                            :ssl-port 5443 ;; ssl-port is used by http/3
                            :keystore "dev-resources/keystore.jks"
                            :key-password "111111"
                            :keystore-type "jks"})
```

Since HTTP/3 runs on UDP, it could share same port with TCP based
protocol like HTTP/2 or 1.1.

An example is available in `examples` folder.

### WebSocket

You can define following handlers for websocket events.

```clojure
(def ws-handler {:on-connect (fn [ws])
                 :on-error (fn [ws e])
                 :on-close (fn [ws status-code reason])
                 :on-text (fn [ws text-message])
                 :on-bytes (fn [ws bytes offset len])
                 :on-ping (fn [ws bytebuffer])
                 :on-pong (fn [ws bytebuffer])})
```

WebSocketProtocol allows you to read and write data on the `ws` value:

* (send! ws msg)
* (send! ws msg callback)
* (ping! ws)
* (ping! ws msg)
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

A callback is a map where keys `:write-failed` and `:write-success`
are optional.

In your ring app, detect a websocket handshake request and upgrade it
with a websocket handler.

```clojure
(require '[ring.adapter.jetty9 :as jetty])

(defn app [req]
  (if (jetty/ws-upgrade-request? req)
    (jetty/ws-upgrade-response ws-handler)))

(run-jetty app)
```

In the javascript:
```javascript
// remember to add the trailing slash.
// Otherwise, jetty will return a 302 on websocket upgrade request,
// which is not supported by most browsers.
var ws = new WebSocket("ws://somehost/loc/");
ws.onopen = ....
```

If you want to omit the trailing slash from your URLs (and not receive
a redirect from Jetty), you can start the server like:
```clojure
(run-jetty app {:allow-null-path-info true})
```

### Websocket Handshake

Sometimes you may have a negotiation with the websocket client on the
handshake (upgrade request) phase. You can define a ring like function
that returns the websocket handler, or raises an error. You may also
select a subprotocol from `(:websocket-subprotocol upgrade-request)` and
configure available `(:websocket-extensions upgrade-request)` via the
websocket handler creator function. See [websocket
example](https://github.com/sunng87/ring-jetty9-adapter/blob/master/examples/rj9a/websocket.clj)
for detail.

## Examples

You can find examples in `examples` folder. To run example:

* http: `lein with-profile example-http run` a very basic
  example of ring handler
* async: `lein with-profile example-async run` ring 1.6 async
  handler example
* http2 `lein with-profile example-http2 run`
* http3 `lein with-profile example-http3 run`
* websocket: `lein with-profile example-websocket run`

## Contributors

* [kristinarodgers](https://github.com/kristinarodgers)
* [xtang](https://github.com/xtang)
* [NoamB](https://github.com/NoamB)
* [mpenet](https://github.com/mpenet)
* [aesterline](https://github.com/aesterline)
* [trptcolin](https://github.com/trptcolin)
* [paomian](https://github.com/paomian)

## License

Copyright © 2013-2021 Sun Ning

Distributed under the Eclipse Public License, the same as Clojure.
