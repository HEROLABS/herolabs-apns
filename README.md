# A clojure Apple Push Notification library

This small library provides a simple facility to send push messages to the
"Apple push notification":http://developer.apple.com/library/mac/#documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/Introduction/Introduction.html
service. The library uses "netty":http://netty.io for the asynchronous communication with the server.


## Build

To build the api-doc plugin you'll have to clone the git repository.

    git clone git(at)github.com:HEROLABS/herolabs-apns.git

Assuming that you have "Leiningen":https://github.com/technomancy/leiningen already installed. Simply execute

    lein install

to install the plugin into your local plugin repository.

## Usage

To integrate the library in you project simply add this to your `project.clj`:

    :dependencies [[herolabs/apns "0.1.0"]]

Sending a push message is quite easy. First we create the message. It's a simple clojure map, but you also may
use some builder/helper functions from `herolabs.apns.message`.

    (def message (-> {}
                 (with-loc-key "GAME_PLAY_REQUEST_FORMAT")
                 (with-loc-args ["Jenna" "Frank"])))

Will create a message like this:

    {:aps {:alert {:loc-args ["Jenna" "Frank"], :loc-key "GAME_PLAY_REQUEST_FORMAT"}}}

The next important step is to create a connecion. The connection will act as proxy for the real connection
to the Apple service. You don't need to open, close or maintain it. The underlying connection management
is handeled by the library and netty.

To create a connection we will need a `ssl-context` and an `address` of the Apple servers. The `address` is the easy part.
You may need the `dev-address` or the `prod-address` to obtain the addresses used by Apple.

To create the `ssl-context` you may use the functions in `herolabs.apns.ssl`. First create a File or URL to your
keystore. How you create this, I'll explain later.

    (def key-store (clojure.java.io/resource "keys/my-keystore"))

Unfortunately the certificates used by Apple are not signed by a major (known by the JRE) authority. So the connection
would not be established by the JRE. You have to choices: a) import the Apple certificates into the JRE keystores (secure)
b) override the trust manager so that he accepts the certificate (not so secure). In this example I chose b.

    (def silly-trust-managers (naive-trust-managers :trace true)))

Now we have everything in place to create the SSLContext to user for the connection.

    (def ctx (ssl-context
          :store-path keystore
          :store-pass "averysecretpassword"
          :cert-pass "anevenbetterpassword"
          :trust-managers silly-trust-managers)

So let's create the connection:

(def connection (push/create-connection (dev-address) ctx))

Now lets send a message:

    (send-message connection "--the-device-token--" message)

a vóila! The message is sent!

Due to the nature of the protocol the feedback is very "limitied". This means, if an error occurs Apple simply closes
the underlying connection. So you don't get any feedback if the message will reach the sender, but that is exactly
the terms Apple supplies. Event the so calles "enhanced" message format delivers some errors about the message format,
but you can't find out if the delivery to the device was successful.

## Feedback Service
To use the push notifications correctly you'll have to check for feedback in a regular fashion. The feedback service
provides a list of device tokens and timestamps of the devices that have your application no longer installed.

Using this service is also pretty simple:

    (doseq [[token timestamp] (feedback (dev-address) ssl-context)]
     (deregister-device token))

The `feedback` function returns a lazy collection that reads the data from the service.  The `herolabs.apns.feedback`
also contain the `dev-address` or the `prod-address` functions to contain the addresses. Be aware that they differ from
the ones used by the push service.

## Creating a JKS keystore

Personally I used this "guide":http://www.agentbob.info/agentbob/79-AB.html .Basically you convert the PEM certificate
which Apple provides into the DER format using `openssl`

    openssl pkcs8 -topk8 -nocrypt -in key.pem -inform PEM -out key.der -outform DER
    openssl x509 -in cert.pem -inform PEM -out cert.der -outform DER

Then you use a small java class ("source":http://www.agentbob.info/agentbob/80/version/default/part/AttachmentData/data/ImportKey.java or
"compiled in Java 1.5":http://www.agentbob.info/agentbob/81/version/default/part/AttachmentData/data/ImportKey.class)
created by "AgentBob - www.agentbob.info.":http://www.agentbob.info/

    java ImportKey key.der cert.der

This creates the keystore `keystore.ImportKey` which you may mow modify with the java keytool.

    mv keystore.ImportKey my-keystore
    keytool -changealias -alias importkey -destalias apple-push-service -keystore my-keystore
    keytool -keypasswd -alias apple-push-service -keystore my-keystore
    keytool -storepasswd -keystore my-keystore

I am pretty sure you will ask, so the the default password of the `keystore.ImportKey` is `importkey`. Neither the
name of the keystore or the key alias matter for the usage with the library. But you have to make sure that you
supply the correct passwords with  `:store-pass` and `:cert-pass` to the library.


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