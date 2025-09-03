# ring-jetty9-adapter (rj9a)

[![CI](https://github.com/sunng87/ring-jetty9-adapter/actions/workflows/clojure.yml/badge.svg)](https://github.com/sunng87/ring-jetty9-adapter/actions/workflows/clojure.yml)
[![Clojars](https://img.shields.io/clojars/v/info.sunng/ring-jetty9-adapter.svg?maxAge=2592000)](https://clojars.org/info.sunng/ring-jetty9-adapter)
[![license](https://img.shields.io/github/license/sunng87/ring-jetty9-adapter.svg?maxAge=2592000)]()
[![Donate](https://img.shields.io/badge/donate-liberapay-yellow.svg)](https://liberapay.com/Sunng/donate)

Ring adapter for Jetty 12 and 11 (although named as jetty9), with HTTP/2,
WebSocket, experimental HTTP/3 and virtual threads support.

This is a simple and plain wrapper on modern Jetty versions. It
doesn't introduce additional thread model or anything else (no
unofficial ring variance, no core.async). You are free to add those on
top of our base API. This library can be used as a drop-in replacement
of original ring-jetty-adapter.

As of Ring 1.6, the official Jetty adapter has been updated to Jetty
9.2. However, rj9a tracks most recent Jetty release and offers
additional features like http/2, http/3 and websocket.

## Version Matrix

rj9a works on top of certain components of Java/Clojure ecosystem. To find a
version works for your stack, check the version matrix below.

| rj9a | Jetty | JDK | Clojure | Servlet API | Ring API | Maintained? |
| --- | --- | --- | --- | --- | --- | --- |
| HEAD | 12.0.x | 17 | 1.12 | N/A | 1.14 | true |
| 0.33.x | 12.0.x | 17 | 1.11 | N/A | 1.12 | false |
| 0.32.x | 12.0.x | 17 | 1.11 | N/A | 1.11 | false |
| 0.30.x | 12.0.x | 17 | 1.11 | N/A | 1.10 | false |
| 0.22.x | 11.0.x | 11 | 1.11 | Jakarta 5.0 | 1.10 | false |
| 0.17.x | 10.0.x | 11 | 1.10 | Jakarta 4.0 | 1.9 | false |
| 0.14.x | 9.4.x | 8 | 1.10 | 3.1 | 1.8 | false |

The `master` branch will keep tracking Jetty 12 as our default release
channel. Jetty versions prior to 12 have reached their EOF (support is only
available with commercial version), so now we are dropping support of them. But
you can still use it.

Note that from 0.33 of rj9a, we dropped dependencies to `ring/ring-core` and
only depend on their protocol artifacts. This won't be a problem if your
applications are just normal ring sync or async handlers. However, for websocket
users, you will need to include `ring/ring-core` explicitly as dependency to be
able to use websocket APIs.

For JDK 8 users, `0.14` releases should be usable but I'm no longer actively
maintaining it.

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

HTTP/2 becomes an optional feature since rj9a `0.35`, to enable HTTP/2 on
cleartext and secure transport, first you need to add our optional dependency:

```
[info.sunng/ring-jetty9-adapter-http2 "VERSION"]
```

`VERSION` can be checked
[here](https://clojars.org/info.sunng/ring-jetty9-adapter-http2)

Second, provide HTTP/2 options to `run-jetty` like:

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

ALPN dependency is required for secure HTTP/2 transport. For rj9a
version `0.17.1` and newer, `org.eclipse.jetty/jetty-alpn-java-server`
is included by default.

For rj9a versions prior to `0.17`, you will need additional dependency
to enable ALPN. Add following dependencies according to the JDK
version you use.

* For JDK 11 and above, add `[org.eclipse.jetty/jetty-alpn-java-server
  ~jetty-version]`
* For OpenJDK 8u252 and above, add
  `[org.eclipse.jetty/jetty-alpn-openjdk8-server ~jetty-version]`
* For OpenJDK 8 prior to update 252, please [check `example-http2-legacy`
  profile](https://github.com/sunng87/ring-jetty9-adapter/blob/0.14.3/project.clj#L26)
  in `project.clj` for boot-classpath configuration
* For any version of JDK users, conscrypt implementation is supported by adding
  `[org.eclipse.jetty/jetty-alpn-conscrypt-server ~jetty-version]` but it's not
  recommended for now because of [memory leak
  issue](https://github.com/google/conscrypt/issues/835)

Note your will need to replace `~jetty-version` with corresponding jetty version
that your version of rj9a uses.

### HTTP/3

From 10.0.9, Jetty ships an experimental HTTP/3 implementation based
on [Jetty's own build](https://github.com/jetty-project/jetty-quiche-native) of
[the quiche library](https://github.com/cloudflare/quiche). rj9a `0.17.6` made
it an optional feature. To enable HTTP/3 support, you will need to:

* In addition to rj9a, add dependency
  `[info.sunng/ring-jetty9-adapter-http3 "VERSION"]` to your clojure
  project to bring in HTTP/3 staff. Remember to replace `VERSION` with our
  latest release, which can be checked
  [here](https://clojars.org/info.sunng/ring-jetty9-adapter-http3)
* Provide certficate and key just like HTTPs setup because HTTP/3 is
  secure by default. There is no plaintext fallback for now.
* Provide option `:http3? true` and a work directory to for pem
  `:http3-pem-work-directory` to `run-jetty` to enable HTTP/3 protocol.

```clojure
(jetty/run-jetty dummy-app {:port 5000  ;; default clear-text http/1.1 port
                            :http3 true  ;; enable http/3 support
                            :http3-pem-work-directory "/some/path" ;; a pre-created directory for quic configuration
                            :ssl-port 5443 ;; ssl-port is used by http/3
                            :keystore "dev-resources/keystore.jks"
                            :key-password "111111"
                            :keystore-type "jks"})
```

Since HTTP/3 runs on UDP, it is possible to share the same port with
TCP based protocol like HTTP/2 or 1.1.

An example is available in `examples` folder.

### WebSocket

From Ring 1.11, Websocket API has been standardized. A valid websocket handler
can be defined as a map of listener functions:

```clojure
(def ws-handler {:on-open (fn [ws])
                 :on-error (fn [ws e])
                 :on-close (fn [ws status-code reason])
                 :on-message (fn [ws message])
                 :on-ping (fn [ws bytebuffer])
                 :on-pong (fn [ws bytebuffer])})
```

Functions are provided in `ring.websocket` to read and write data on the `ws`
value:

* `(ring.websocket/send ws msg)`
* `(ring.websocket/send ws msg succeed-callback fail-callback)`
* `(ring.websocket/ping ws msg)`
* `(ring.websocket/close ws)`

Notice that we support different type of msg:

* **String**: send text websocket message
* **ByteBuffer**: send binary websocket message

In your ring app, detect a websocket handshake request and upgrade it
with a websocket handler.

```clojure
(require '[ring.adapter.jetty9 :as jetty])

(defn app [req]
  (if (jetty/ws-upgrade-request? req)
    {:ring.websocket/listener ws-handler}))

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
select a subprotocol from `(:websocket-subprotocol upgrade-request)` via the
websocket handler creator function. See [websocket
example](https://github.com/sunng87/ring-jetty9-adapter/blob/master/examples/rj9a/websocket.clj)
for detail.

## Examples

You can find examples in `examples` folder. To run example:

* http: `lein with-profile example-http run` a very basic
  example of ring handler
* async: `lein with-profile example-async run` ring 1.6 async
  handler example
* http2: `lein with-profile example-http2 run`
* http3: `lein with-profile example-http3 run`
* websocket: `lein with-profile example-websocket run`

## License

Copyright © 2013-2024 Sun Ning

Distributed under the Eclipse Public License, the same as Clojure.

## Sponsors

This project is sponsored by:

- [TOYOKUMO](https://github.com/toyokumo) sponsored this project in [Thanks OSS
  Award](https://oss.toyokumo.co.jp/) program.
