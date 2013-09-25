(ns herolabs.apns.push
  (:use [clojure.tools.logging]
        [herolabs.apns.ssl :only (ssl-context ssl-engine ssl-engine-factory)]
        [herolabs.apns.protocol :only (codec)]
        [clojure.stacktrace])
  (:import [io.netty.channel Channel ChannelFuture ChannelPromise ChannelHandlerContext ChannelInitializer ChannelDuplexHandler]
           [io.netty.channel.socket SocketChannel]
           [io.netty.channel.socket.nio NioSocketChannel]
           [io.netty.channel.nio NioEventLoopGroup]
           [io.netty.bootstrap Bootstrap]
           [io.netty.handler.ssl SslHandler]
           [io.netty.handler.timeout IdleStateHandler IdleState]
           [java.util.concurrent.atomic AtomicInteger]
           [java.net InetSocketAddress SocketAddress]
           [javax.net.ssl SSLContext]))


(def ^:private default-thread-pool* (atom nil))

(defmacro future-listener [params & body]
  (cond
    (not (vector? params)) (throw (IllegalArgumentException. "Parameter have to be a vector."))
    (not= 1 (count params)) (throw (IllegalArgumentException. "Parameter may only contain one element."))
    (empty? body) nil
    :else (let [future (first params)]
            `(reify io.netty.channel.ChannelFutureListener
               (operationComplete [this# ^ChannelFuture ~future] ~@body)))))


(def ^:private handler-names [:bind-handler :close-handler :connect-handler :disconnect-handler :deregister-handler :flush-handler :read-handler :write-handler :exception-handler :channel-active-handler :channel-inactive-handler :channel-read-complete-handler :channel-registered-handler :channel-unregistered-handler :channel-writablility-changed-handler :user-event-triggered-handler :exception-caught-handler ])
(def ^:private default-handlers {:connect-handler (fn connect-handler [_ remote-address _ _] (info "Connected to " remote-address))
                                 :user-event-triggered-handler (fn user-event-triggered [^ChannelHandlerContext ctx event]
                                                                 (cond
                                                                   (instance? IdleState event) (.close ctx)
                                                                   :else nil)
                                                                 )
                                 :channel-inactive-handler (fn channel-inactive-handler [ctx] (debug "Connection to" (if-let [c (.channel ctx)] (.remoteAddress c) "-") "went inactive."))
                                 :exception-caught-handler (fn exception-caught [^ChannelHandlerContext ctx ^Throwable cause]
                                                             (let [^Channel channel (.channel ctx)]
                                                               (debug cause "An exception occured on channel to" (if channel (.getRemoteAddress channel) "-") ", closing channel ...")
                                                               (.close channel)))})

(defn- handler
  "Function to create a ChannelDuplexHandler"
  [handlers]
  (proxy [io.netty.channel.ChannelDuplexHandler] []
    (bind [^ChannelHandlerContext ctx ^SocketAddress local-address ^ChannelPromise future]
      (when-not (when-let [h (get handlers :bind-handler )]
                  (h ctx local-address future)
                  (:no-super (meta h)))
        (proxy-super bind ctx local-address future)))
    (connect [^ChannelHandlerContext ctx ^SocketAddress remote-address ^SocketAddress local-address ^ChannelPromise future]
      (when-not (when-let [h (get handlers :connect-handler )]
                  (h ctx remote-address local-address future)
                  (:no-super (meta h)))
        (proxy-super connect ctx remote-address local-address future)))
    (disconnect [^ChannelHandlerContext ctx ^ChannelPromise future]
      (when-not (when-let [h (get handlers :disconnect-handler )]
                  (h ctx future)
                  (:no-super (meta h)))
        (proxy-super disconnect ctx future)))
    (close [^ChannelHandlerContext ctx ^ChannelPromise future]
      (when-not (when-let [h (get handlers :close-handler )]
                  (h ctx future)
                  (:no-super (meta h)))
        (proxy-super close ctx future)))
    (deregister [^ChannelHandlerContext ctx ^ChannelPromise future]
      (when-not (when-let [h (get handlers :deregister-handler )]
                  (h ctx future)
                  (:no-super (meta h)))
        (proxy-super deregister ctx future)))
    (read [^ChannelHandlerContext ctx]
      (when-not (when-let [h (get handlers :read-handler )]
                  (h ctx)
                  (:no-super (meta h)))
        (proxy-super read ctx)))
    (write [^ChannelHandlerContext ctx msg ^ChannelPromise future]
      (when-not (when-let [h (get handlers :write-handler )]
                  (h ctx msg future)
                  (:no-super (meta h)))
        (proxy-super write ctx msg future)))
    (flush [^ChannelHandlerContext ctx]
      (when-not (when-let [h (get handlers :flush-handler )]
                  (h ctx)
                  (:no-super (meta h)))
        (proxy-super flush ctx)))
    (channelRegistered [^ChannelHandlerContext ctx]
      (when-not (when-let [h (get handlers :channel-registered-handler )]
                  (h ctx)
                  (:no-super (meta h)))
        (proxy-super channelRegistered ctx)))
    (channelUnregistered [^ChannelHandlerContext ctx]
      (when-not (when-let [h (get handlers :channel-unregistered-handler )]
                  (h ctx)
                  (:no-super (meta h)))
        (proxy-super channelInactive ctx)))
    (channelActive [^ChannelHandlerContext ctx]
      (when-not (when-let [h (get handlers :channel-active-handler )]
                  (h ctx)
                  (:no-super (meta h)))
        (proxy-super channelActive ctx)))
    (channelInactive [^ChannelHandlerContext ctx]
      (when-not (when-let [h (get handlers :channel-inactive-handler )]
                  (h ctx)
                  (:no-super (meta h)))
        (proxy-super channelInactive ctx)))
    (channelRead [^ChannelHandlerContext ctx msg]
      (when-not (when-let [h (get handlers :channel-read-handler )]
                  (h ctx msg)
                  (:no-super (meta h)))
        (proxy-super channelRead ctx msg)))
    (channelReadComplete [^ChannelHandlerContext ctx]
      (when-not (when-let [h (get handlers :channel-read-complete-handler )]
                  (h ctx)
                  (:no-super (meta h)))
        (proxy-super channelReadComplete ctx)))
    (userEventTriggered [^ChannelHandlerContext ctx evt]
      (when-not (when-let [h (get handlers :user-event-triggered-handler )]
                  (h ctx evt)
                  (:no-super (meta h)))
        (proxy-super userEventTriggered ctx evt)))
    (channelWritabilityChanged [^ChannelHandlerContext ctx evt]
      (when-not (when-let [h (get handlers :channel-writability-changed-handler )]
                  (h ctx evt)
                  (:no-super (meta h)))
        (proxy-super channelWritabilityChanged ctx ^Throwable evt)))
    (exceptionCaught [^ChannelHandlerContext ctx cause]
      (when-not (when-let [h (get handlers :exception-caught-handler )]
                  (h ctx cause)
                  (:no-super (meta h)))
        (proxy-super exceptionCaught ctx cause)))))

(defn- create-channel-initializer [ssl-engine protocoll-handler time-out]
  "Creates a pipeline factory"
  (proxy [ChannelInitializer] []
    (initChannel [^SocketChannel channel]
      (let [id-gen (AtomicInteger. (rand-int 1000))
            pipeline (.pipeline channel)]
        (doto pipeline
          (.addLast "ssl" (SslHandler. ssl-engine))
          (.addLast "codec" (codec id-gen nil nil))
          (.addLast "idleStateHandler" (IdleStateHandler. 0 time-out 0))
          (.addLast "protocollHandler" protocoll-handler))))))

(defn- default-exception-handler [cause] (info cause "An exception occured while sending push notification to the server."))

(defn- connect [^InetSocketAddress address ^SSLContext ssl-context time-out handlers event-loop]
  "Creates a Netty Channel to connect to the server."
  (let [ssl-engine (ssl-engine ssl-context :use-client-mode true)
        bootstrap (-> (Bootstrap.)
                    (.group event-loop)
                    (.channel NioSocketChannel)
                    (.handler (create-channel-initializer ssl-engine (handler handlers) time-out))
                    )
        future (-> bootstrap (.connect address) (.sync))
        channel (.channel future)
        ]
    channel
    ))

(defprotocol Connection
  (is-active? [this] "Determines is a connection is connected")
  (write-message [this message] "Writes a message"))

(defn success?
  "Checks of a future finished successful. Also waits uninterruptibly until the future finished to determine the result."
  [^ChannelFuture future] (when future (-> future (.awaitUninterruptibly) (.isSuccess))))

(deftype NettyConnection [^SocketChannel channel]
  Connection
  (is-active? [_] (when-let [^Channel channel channel] (.isActive channel)))
  (write-message [_ message] (.writeAndFlush channel message))
  java.io.Closeable
  (close [this] (.close channel)))


(def ^:private default-event-loop (NioEventLoopGroup.))

(defn create-connection
  "Creates a connection the the Apple push notification service."
  [^InetSocketAddress address
   ^SSLContext ssl-context
   & {:keys [time-out event-loop]
      :or {time-out 300
           event-loop default-event-loop}
      :as params}]
  (let [handlers (merge default-handlers (select-keys (into {} params) handler-names))
        channel (connect address ssl-context time-out handlers event-loop)]
    (when channel (NettyConnection. channel))))

(defn send-message
  "Sends a message in the standard message format to the Apple push service"
  [^herolabs.apns.push.Connection connection ^String device-token message & {:keys [completed-listener]}]
  (when (and connection device-token message)
    (loop [[listener & rest] (if (sequential? completed-listener) completed-listener [completed-listener])
           ^ChannelFuture future (.write-message connection (with-meta message {:device-token device-token}))]
      (if listener (recur rest (.addListener future listener)) future))))

(defn send-enhanced-message
  "Sends a message in the enhanced message format to the Apple push service"
  [^herolabs.apns.push.Connection connection ^String device-token message & {:keys [completed-listener]}]
  (when (and connection device-token message)
    (loop [[listener & rest] (if (sequential? completed-listener) completed-listener [completed-listener])
           ^ChannelFuture future (.write-message connection (with-meta message {:device-token device-token :format :enhanced}))]
      (if listener (recur rest (.addListener future listener)) future))))

(defn dev-address
  "The Apple sandbox address for the push service."
  []
  (InetSocketAddress. "gateway.sandbox.push.apple.com" 2195))

(defn prod-address
  "The productive Apple internet address for the push service."
  []
  (InetSocketAddress. "gateway.push.apple.com" 2195))
