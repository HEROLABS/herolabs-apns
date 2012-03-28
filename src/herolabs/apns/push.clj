(ns herolabs.apns.push
  (:use [clojure.tools.logging]
        [herolabs.apns.ssl :only (ssl-context ssl-engine)]
        [herolabs.apns.protocol :only (encoder decoder)]
        [clojure.stacktrace])
  (:import [org.jboss.netty.channel Channel ChannelFuture Channels ChannelPipeline ChannelPipelineFactory ChannelEvent
            ChannelHandlerContext ChannelStateEvent ExceptionEvent MessageEvent WriteCompletionEvent
            SimpleChannelUpstreamHandler]
           [org.jboss.netty.channel.socket.nio NioClientSocketChannelFactory]
           [org.jboss.netty.bootstrap ClientBootstrap]
           [org.jboss.netty.handler.ssl SslHandler]
           [org.jboss.netty.handler.timeout WriteTimeoutHandler]
           [org.jboss.netty.util HashedWheelTimer]
           [java.util.concurrent Executors]
           [java.util.concurrent.atomic AtomicInteger]
           [java.net InetSocketAddress]
           [javax.net.ssl SSLContext]
           )
  )


(defn- handler [& {:keys [exception-handler close-handler]}]
  "Function to create a ChannelUpstreamHandler"
  (proxy [org.jboss.netty.channel.SimpleChannelUpstreamHandler] []
    (channelConnected [^ChannelHandlerContext ctx ^ChannelStateEvent event]
      (debug "channelConnected")
      (let [ssl-handler (-> ctx
        (.getPipeline)
        (.get SslHandler))]
        (.handshake ssl-handler)
        ))
    (messageReceived [^ChannelHandlerContext ctx ^MessageEvent event]
      (debug "messageReceived -" (.getMessage event))
      )
    (exceptionCaught [^ChannelHandlerContext ctx ^ExceptionEvent event]
      (debug (.getCause event) "exceptionCaught")
      (when exception-handler (exception-handler (.getCause event)))
      (-> event
        (.getChannel)
        (.close))
      )
    (channelClosed [^ChannelHandlerContext ctx ^ChannelStateEvent event]
      (debug "channelClosed")
      (when close-handler (close-handler))
      )
    ))



(defn- create-pipeline-factory [ssl-engine handler time-out]
  "Creates a pipeline factory"
  (reify
    ChannelPipelineFactory
    (getPipeline [this]
      (let [id-gen (AtomicInteger.)
            timer (HashedWheelTimer.)]
        (doto (Channels/pipeline)
          (.addLast "ssl" (SslHandler. ssl-engine))
          (.addLast "encoder" (encoder id-gen))
          (.addLast "decoder" (decoder))
          (.addLast "timeout" (WriteTimeoutHandler. timer (int (if time-out time-out 300))))
          (.addLast "handler" handler)
          ))
      )
    )
  )

(defn- connect [^InetSocketAddress address ^SSLContext ssl-context time-out & {:keys [exception-handler close-handler]}]
  "creates a netty Channel to connect to the server."
  (let [engine (ssl-engine ssl-context :use-client-mode true)
        pipeline-factory (create-pipeline-factory engine (handler
                                                           :exception-handler exception-handler
                                                           :close-handler close-handler) time-out)
        bootstrap (doto (-> (NioClientSocketChannelFactory.
                              (Executors/newCachedThreadPool)
                              (Executors/newCachedThreadPool))
                          (ClientBootstrap.))
      (.setOption "connectTimeoutMillis" 5000)
      (.setPipelineFactory pipeline-factory))
        future (.connect bootstrap address)
        channel (-> future
      (.awaitUninterruptibly)
      (.getChannel)
      )]
    (if (.isSuccess future)
      channel
      (do
        (.releaseExternalResources bootstrap)
        nil
        )
      )
    )
  )

(defn- ensure-connected! [channel ^InetSocketAddress address ^SSLContext ssl-context time-out]
  "Internal function to ensure that a channel ref is not nil and the underlying channel is connected.
  Returns always a connected channel and updates the channel ref if neccessary."
  (let [c @channel]
    (if-not (and c (.isConnected c))
      (swap! channel (fn [_] (connect address ssl-context time-out
                               :exception-handler (fn [cause]
                                                    (info cause "An exception occured while sending push notification to the server.")
                                                    )
                               :close-handler (fn []
                                                (debug "Resetting internal channel, due to close.")
                                                (reset! channel nil)))))
      c
      )
    )
  )

(defprotocol Connection
  (is-connected? [this] "Determines is a connection is connected")
  (write-message [this message] "Writes a message")
  (disconnect [this] "Disconnects a connection from the server")
  )

(deftype ApnsConnection [^InetSocketAddress address ^SSLContext ssl-context time-out channel]
  Connection
  (is-connected? [_]
    (let [c @channel]
      (and c (.isConnected c)))
    )
  (write-message [_ message]
    (let [c (ensure-connected! channel address ssl-context time-out)]
      (.write c message)))
  (disconnect [_]
    (swap! channel (fn [c]
                     (when c (.close c))
                     nil
                     )))
  )

(defprotocol Result
  (success? [this] "Determines if the send operation was a success.")
  (done? [this] "Checks if the operation already competed.")
  )


(deftype SendResult [^ChannelFuture future]
  Result
  (success? [_] (-> future
                  (.awaitUninterruptibly)
                  (.isSuccess)))
  (done? [_] (-> future
               (.isDone)))
  )

(defn create-connection [^InetSocketAddress address ^SSLContext ssl-context & {:keys [time-out] :or [time-out 300]}]
  "Creates a connection"
  (ApnsConnection. address ssl-context time-out (atom nil))
  )

(defn send-message [^herolabs.apns.push.Connection connection ^String device-token message]
  "Sends a message in the standard message format to the Apple push service"
  (when (and connection device-token message)
    (let [msg (with-meta message {:device-token device-token})]
      (SendResult. (.write-message connection msg))
      )))

(defn send-enhanced-message [^herolabs.apns.push.Connection connection ^String device-token message]
  "Sends a message in the enhanced message format to the Apple push service"
  (when (and connection device-token message)
    (let [msg (with-meta message {:device-token device-token :format :enhanced})]
      (SendResult. (.write-message connection msg))
      )))

(defn dev-address []
  (InetSocketAddress. "gateway.sandbox.push.apple.com" 2195)
  )

(defn prod-address []
  (InetSocketAddress. "gateway.push.apple.com" 2195)
  )