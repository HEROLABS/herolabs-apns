# A clojure Apple Push Notification library

This small library provides a simple facility to send push messages to the
[Apple push notification](https://developer.apple.com/library/ios/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/Chapters/WhatAreRemoteNotif.html)
service. The library uses [netty](http://netty.io) for the asynchronous communication with the server.

**Warning:** The 0.5.x versions have a major change in behaviour. The connection instance in the 0.2.x versions tried to re-connect when it was closed, e.g. after disconnect by Apple due to a faulty message.
The connection in the 0.5.x instance will stay closed and you have to create a new one manually. This is motivated by the error handling which is new to the 0.5.x series. See _Error handling_ for more information.

## Build

To build the api-doc plugin you'll have to clone the git repository.

    git clone git(at)github.com:HEROLABS/herolabs-apns.git

Assuming that you have [Leiningen](https://github.com/technomancy/leiningen) already installed. Simply execute

    lein install

to install the plugin into your local plugin repository.

## Usage

To integrate the library in you project simply add this to your `project.clj`:

    :dependencies [[herolabs/apns "0.5.0"]]

Sending a push message is quite easy. First we create the message. It's - more or less - a simple clojure map, but you also may use some builder/helper functions from `herolabs.apns.message`. When you use the builder function to may also supply informations like the recipent (`to`) or other meta-informations like the priority or the expiry date.

    (-> (to "SOME_APS_TOKEN")
      (with-loc-key "GAME_PLAY_REQUEST_FORMAT")
      (with-loc-args ["Jenna" "Frank"]))

Will create a message like this:

    {:aps {:alert {:loc-args ["Jenna" "Frank"], :loc-key "GAME_PLAY_REQUEST_FORMAT"}}}

Meta data like the recipient, etc. will be stored as Clojure meta-data on the map.

The next important step is to create a connecion. The connection will act as proxy for the real connection
to the Apple service. You don't need to open, close or maintain it. The underlying connection management
is handeled by the library and netty.

## Creating a connection
To create a connection we will need a `ssl-context` and an `address` of the Apple servers. The `address` is the easy part.
You may need the `dev-address` or the `prod-address` to obtain the addresses used by Apple.

To create the `ssl-context` you may use the functions in `herolabs.apns.ssl`. First create a Files or URLs to your
certificate and key files. Then you can use the `keystore` function to create a transient keystore containing the key and the certificate.

	(let [key-file (resource "files/my-project.p12")
	      cert-file (resource "files/my-project.cer")
	      store (ssl/keystore :key-path key-
	      					  :key-pass "verysecretkeypass"
	      					  :cert-path cert-file)]
		...
	)


Unfortunately the certificates used by Apple are not signed by a major (known by the JRE) authority. So the connection
would not be established by the JRE. You have to choices: a) import the Apple certificates into the JRE keystores (secure)
b) override the trust manager so that he accepts the certificate (not so secure). In this example I chose b.

Now lets have a look how to create the context and connection:

	(let [silly-trust-managers (naive-trust-managers :trace true)
		  ctx (ssl/ssl-context :keystore store :trust-managers silly-trust-managers)
		  connection (push/create-connection (dev-address) ctx)]
		...
	)


Now lets send a message:

    (send connection message)

Due to the nature of the protocol the feedback is very "limitied". The 0.5.x-Series uses the most recent protocol version of the Apple service, so the feedback got better.


### Error Handling
The error handling is new to the 0.5.x version series and a little more manual/explicit than before.

Some good news first, you are able to provide some handling functions for almost every event Netty is providing. E.g. a `:disconnect-handler` to write something when the connection was disconnected:

	(push/create-connection (dev-address) ctx :disconnect-handler (fn [_ _] (println "disconnected.")))

Simply supply the the addional parameter on connection creation.

The most intersting handler to supply are the `:channel-inactive-handler` and the `:error-handler` - which is something APS specific.

The `:channel-inactive-handler` is called when the connection is closed and the underlying channel ist disconnected from the Netty EventLoop. You max pass an arity 2 function which will receive the `ChannelHandlerContext` and a list of messages which where sent to the APS service but should be re-sent. For details [look at the APS dokumentation](https://developer.apple.com/library/ios/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/Chapters/WhatAreRemoteNotif.html) and continue reading.

The `:error-handler` is a more convenient `:channel-read-handler` (therefore you may only supply one or the other when creating a connection). The only response Apple sends is an error response before disconnecting. The error handler will be called when receiving the error message from apple. You may supply an one argument function which will get a map like this:

	{:status :invalid-token :id 3892 :faulty {:aps { ... }} :resent ()}

* `status`: The status flag of the response from Apple
* `id`: The internal ID of the message in the context of the current connection
* `faulty`: The message which caused the error
* `resent`: The messages which are propably lost at Apple and should be re-sent.

One more word on this messages that gone missing. Apple does not process any message after the faulty one. So the connection remembers (and forgets them after 15 seconds) of all messages sent. When the error message is received all messages before the faulty will also removed, so you get the ones which where sent and possibily not processed.

So how to do the error handling:

```clojure
(def ^:private aps-connection (atom nil))

(declare connection)

(defn- resent-handler [_ re-sent]
  (doseq [msg re-sent] (push/send (connection) msg)))

(defn- error-handler [error]
  (reset! aps-connection nil)
  (let [status (:status error)
        faulty (:faulty error)
        device-token (:devive-token (meta faulty))]
  (when (and (= :invalid-token status) device-token)
  (delete-token-from-somewhere device-token))))

(defn connection []
    (or @aps-connection
      (swap! aps-connection (fn [_]
                               (when-let [ssl-context (get-ssl-context)]
                                 (let [address (if (= :prod mode) (push/prod-address) (push/dev-address))]
                                   (push/create-connection address ssl-context
                                     :channel-inactive-handler resent-handler
                                     :error-handler error-handler)))))))
```

I defined two handler functions. One which handle the re-sending of the messages and the other removes the cached connection and e.g. deletes tokens from my database when the token is invalid.

## Feedback Service
To use the push notifications correctly you'll have to check for feedback in a regular fashion. The feedback service
provides a list of device tokens and timestamps of the devices that have your application no longer installed.

Using this service is also pretty simple:

    (doseq [[token timestamp] (feedback (dev-address) ssl-context)]
     (deregister-device token))

The `feedback` function returns a lazy collection that reads the data from the service.  The `herolabs.apns.feedback`
also contain the `dev-address` or the `prod-address` functions to contain the addresses. Be aware that they differ from
the ones used by the push service.



## License

Copyright (c) 2012 HEROLABS GmbH. All rights reserved.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
which can be found in the file epl-v10.html at the root of this distribution.
By using this software in any fashion, you are agreeing to be bound by
the terms of this license.
You must not remove this notice, or any other, from this software.

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations under
the License.
