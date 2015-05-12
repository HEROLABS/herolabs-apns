(ns herolabs.apns.feedback
  (:use [clojure.tools.logging]
        [herolabs.apns.ssl :only (ssl-context ssl-engine)]
        [herolabs.apns.protocol :only (feedback-decoder)]
        [clojure.stacktrace])
  (:import [io.netty.channel Channel ChannelFuture ChannelPromise ChannelHandlerContext ChannelInitializer ChannelDuplexHandler]
           [io.netty.channel.socket SocketChannel]
           [io.netty.channel.socket.nio NioSocketChannel]
           [io.netty.channel.nio NioEventLoopGroup]
           [io.netty.bootstrap Bootstrap]
           [io.netty.handler.ssl SslHandler]
           [io.netty.handler.timeout IdleState IdleStateHandler]
           [java.util.concurrent Executors ExecutorService ThreadFactory]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]
           [java.net InetSocketAddress]
           [javax.net.ssl SSLContext]))



(defn- handler
  "Function to create a ChannelUpstreamHandler"
  [^LinkedBlockingQueue queue]
  (proxy [io.netty.channel.ChannelInboundHandlerAdapter] []
    (channelRead [^ChannelHandlerContext ctx msg]
      (.put queue msg))
    (exceptionCaught [^ChannelHandlerContext ctx ^Throwable cause]
      (let [^Channel channel (.channel ctx)]
        (debug cause "An exception occured on channel to" (if channel (.getRemoteAddress channel) "-") ", closing channel ...")
        (.close channel)))
    (channelActive [^ChannelHandlerContext ctx]
      (debug "Channel to "(if-let [c (.channel ctx)] (.remoteAddress c) "-") " got activated."))
    (channelInactive [^ChannelHandlerContext ctx]
      (debug "Channel got inactive"))
    (userEventTriggered [^ChannelHandlerContext ctx event]
      (cond
        (instance? IdleState event) (.close ctx)
        :else nil))))


(defn- create-channel-initializer
  "Creates a pipeline factory"
  [ssl-engine handler time-out]
  (proxy [ChannelInitializer] []
    (initChannel [^SocketChannel channel]
      (let [pipeline (.pipeline channel)]
        (doto pipeline
          (.addLast "ssl" (SslHandler. ssl-engine))
          (.addLast "decoder" (feedback-decoder))
          (.addLast "timeout" (IdleStateHandler. 0 time-out 0))
          (.addLast "protocollHandler" handler))))))

(defn- connect
  "creates a netty Channel to connect to the server."
  [^InetSocketAddress address ^SSLContext ssl-context queue time-out event-loop]
  (try
    (let [ssl-engine (ssl-engine ssl-context :use-client-mode true)
          bootstrap (-> (Bootstrap.)
                      (.group event-loop)
                      (.channel NioSocketChannel)
                      (.handler (create-channel-initializer ssl-engine (handler queue) time-out))
                      )

          future (-> bootstrap (.connect address) (.sync))
          channel (.channel future)
          ]
      (if (.isSuccess future)
        channel
        nil))
    (catch java.lang.Exception e
      (warn e "An error occure while connecting to Apple feedback service."))))


(defn- read-feedback
  "Internal function to create a lazy-seq returning the data from the feedback service"
  [^LinkedBlockingQueue queue ^Channel channel]
  (lazy-seq
    (if-let [next (.poll queue 10 TimeUnit/SECONDS)]
      (cons next (read-feedback queue channel))
      (when (.isActive channel)
        (.close channel)
        nil))))

(def ^:private default-event-loop (NioEventLoopGroup.))


(defn feedback
  "Creates a seq with the results from the feedback service"
  [^InetSocketAddress address ^SSLContext ssl-context & {:keys [time-out event-loop]
                                                                      :or {time-out 30
                                                                           event-loop default-event-loop}}]
  (let [queue (LinkedBlockingQueue.)
        channel (connect address ssl-context queue time-out event-loop)]
    (read-feedback queue channel)))

(defn dev-address [] (InetSocketAddress. "feedback.sandbox.push.apple.com" 2196))

(defn prod-address [] (InetSocketAddress. "feedback.push.apple.com" 2196))
