(ns herolabs.apns.push
  (:refer-clojure :exclude [send])
  (:use [clojure.tools.logging]
        [herolabs.apns.ssl :only (ssl-context ssl-engine ssl-engine-factory)]
        [herolabs.apns.protocol :only (codec queue-entry-comparator)]
        [clojure.stacktrace])
  (:import [io.netty.channel Channel ChannelFuture ChannelPromise ChannelHandlerContext ChannelInitializer ChannelDuplexHandler]
           [io.netty.channel.socket SocketChannel]
           [io.netty.channel.socket.nio NioSocketChannel]
           [io.netty.channel.nio NioEventLoopGroup]
           [io.netty.bootstrap Bootstrap]
           [io.netty.handler.ssl SslHandler]
           [io.netty.handler.timeout IdleStateHandler IdleState IdleStateEvent]
           [java.util.concurrent.atomic AtomicInteger]
           [java.net InetSocketAddress SocketAddress]
           [javax.net.ssl SSLContext]
           [java.util.concurrent TimeUnit]))


(defonce ^:private connection-id-generator (AtomicInteger.))

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
                                                                   (instance? IdleStateEvent event) (-> ctx (.channel) (.close))
                                                                   :else nil))
                                 :channel-inactive-handler (fn channel-inactive-handler [ctx sent-queue] (debug "Connection" (if-let [c (.channel ctx)] (.remoteAddress c) "-") "went inactive." (count sent-queue) "messages should be resent."))
                                 :exception-caught-handler (fn exception-caught [^ChannelHandlerContext ctx ^Throwable cause]
                                                             (let [^Channel channel (.channel ctx)]
                                                               (debug cause "An exception occured on channel to" (if channel (.getRemoteAddress channel) "-") ", closing channel ...")
                                                               (.close channel)))})

#_ (defn tracing-handler [handler] (fn [^ChannelHandlerContext ctx & other] (log (name handler) "-" other)))

#_ (def tracing-handlers (into {} (for [handler handler-names] [handler (logging-handler handler)])))


(defn- handler
  "Function to create a ChannelDuplexHandler"
  [handlers sent-queue]
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
                  (h ctx (map #(nth % 2) @sent-queue))
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

(defn- create-channel-initializer [ssl-engine protocoll-handler sent-queue id-gen time-out expires priority]
  "Creates a pipeline factory"
  (proxy [ChannelInitializer] []
    (initChannel [^SocketChannel channel]
      (let [pipeline (.pipeline channel)]
        (doto pipeline
          (.addLast "ssl" (SslHandler. ssl-engine))
          (.addLast "codec" (codec id-gen sent-queue expires priority))
          (.addLast "idleStateHandler" (IdleStateHandler. 0 time-out 0))
          (.addLast "protocollHandler" protocoll-handler))))))

(defn- default-exception-handler [cause] (warn cause "An exception occured while sending push notification to the server."))


(defn- connect [^InetSocketAddress address ^SSLContext ssl-context handlers event-loop id-generator time-out expires priority]
  "Creates a Netty Channel to connect to the server."
  (let [ssl-engine (ssl-engine ssl-context :use-client-mode true)
        sent-queue (atom (sorted-set-by queue-entry-comparator))
        bootstrap (-> (Bootstrap.)
                    (.group event-loop)
                    (.channel NioSocketChannel)
                    (.handler (create-channel-initializer ssl-engine (handler handlers sent-queue) sent-queue id-generator
                                time-out expires priority))
                    )
        future (-> bootstrap (.connect address) (.sync))
        ]
    (when (.isSuccess future)
      (let [channel (.channel future)
            schedule-future (.scheduleWithFixedDelay event-loop (fn sent-queue-reaper []
                                                                  (let [threshold (- (System/currentTimeMillis) 15000)]
                                                                    (swap! sent-queue (fn [msgs] (apply sorted-set-by queue-entry-comparator (remove #(let [t (second %)] (< t threshold)) msgs))))
                                                                    #_ (debug "Shortended sent queue to" (count @sent-queue) "entries.")
                                                                    )) 15 15 java.util.concurrent.TimeUnit/SECONDS)
            close-future (.closeFuture channel)]
        (.addListener close-future (future-listener [f]
                                     (.cancel schedule-future false)
                                     (debug "Cancelled sent-queue reaper.")))
        channel
        ))))

(defprotocol Connection
  (is-active? [this] "Determines is a connection is connected")
  (write-message [this message] "Writes a message")
  (active-since [this] "Returns the date (as long) since when the connection is active.")
  (messages-sent [this] "Returns the number of messages sent."))

(defn success?
  "Checks of a future finished successful. Also waits uninterruptibly until the future finished to determine the result."
  [^ChannelFuture future] (when future (-> future (.awaitUninterruptibly) (.isSuccess))))

(deftype NettyConnection [id ^SocketChannel channel since ^AtomicInteger counter]
  Connection
  (is-active? [_] (when-let [^Channel channel channel] (.isActive channel)))
  (write-message [_ message] (.writeAndFlush channel message))
  (active-since [_] since)
  java.io.Closeable
  (close [this] (.close channel))
  java.lang.Object
  (toString [_] (str "NettyConnection("id":" (-> channel (.remoteAddress))))
  (messages-sent [this] (.get counter)))


(def ^:private default-event-loop (NioEventLoopGroup.))

(defn create-connection
  "Creates a connection the the Apple push notification service."
  [^InetSocketAddress address
   ^SSLContext ssl-context
   & {:keys [error-handler time-out event-loop expires priority]
      :or {time-out 300
           event-loop default-event-loop}
      :as params}]
  (let [handlers (merge default-handlers (select-keys (into {} params) handler-names))
        handlers (if-not error-handler handlers
                   (if (:channel-read-handler params)
                     (throw (IllegalArgumentException. "Either supply :error-handler or :channel-read-handler."))
                     (assoc handlers :channel-read-handler (fn [ctx msg] (error-handler msg)))))
        counter (AtomicInteger. 0)
        channel (connect address ssl-context handlers event-loop counter time-out expires priority)]
    (when channel (NettyConnection. (.getAndIncrement connection-id-generator) channel (System/currentTimeMillis) counter))))

(defn send-to
  "Sends a message in the standard message format to the Apple push service"
  [^herolabs.apns.push.Connection connection ^String device-token message & {:keys [completed-listener]}]
  (when (and connection device-token message)
    (loop [[listener & rest] (if (sequential? completed-listener) completed-listener [completed-listener])
           ^ChannelFuture future (.write-message connection (with-meta message {:device-token device-token}))]
      (if listener (recur rest (.addListener future listener)) future))))

(defn send
  "Sends a message in the standard message format to the Apple push service"
  [^herolabs.apns.push.Connection connection message & {:keys [completed-listener]}]
  (when (and connection message)
    (loop [[listener & rest] (if (sequential? completed-listener) completed-listener [completed-listener])
           ^ChannelFuture future (.write-message connection message)]
      (if listener (recur rest (.addListener future listener)) future))))


(defn dev-address
  "The Apple sandbox address for the push service."
  []
  (InetSocketAddress. "gateway.sandbox.push.apple.com" 2195))

(defn prod-address
  "The productive Apple internet address for the push service."
  []
  (InetSocketAddress. "gateway.push.apple.com" 2195))
