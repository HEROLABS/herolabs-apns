(ns herolabs.apns.push
  (:use [clojure.tools.logging]
        [herolabs.apns.ssl :only (ssl-context ssl-engine ssl-engine-factory)]
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
           [org.jboss.netty.handler.execution ExecutionHandler OrderedMemoryAwareThreadPoolExecutor]
           [java.util.concurrent Executors ExecutorService ThreadFactory]
           [java.util.concurrent.atomic AtomicInteger]
           [java.net InetSocketAddress]
           [javax.net.ssl SSLContext]))


(def ^:private default-thread-pool* (atom nil))

(defn default-thread-pool []
  (or @default-thread-pool*
    (swap! default-thread-pool*
      (fn [_] (Executors/newCachedThreadPool
                (let [number (atom 1)
                      sm (System/getSecurityManager)
                      group (if sm (.getThreadGroup sm) (.getThreadGroup (Thread/currentThread)))]
                  (reify ThreadFactory
                    (newThread [_ r] (let [t (Thread. group r (str "apns-push-pool-" (swap! number inc)) 0)]
                                       (when (.isDaemon t) (.setDaemon t false))
                                       (when (not= Thread/NORM_PRIORITY (.getPriority t)) (.setPriority t Thread/NORM_PRIORITY))
                                       t)))))))))

(def ^:private timer* (ref nil))

(defn- timer [] (or @timer* (dosync (alter timer* (fn [_] (HashedWheelTimer.))))))

(defmacro future-listener [params & body]
  (cond
    (not (vector? params)) (throw (IllegalArgumentException. "Parameter have to be a vector."))
    (not= 1 (count params)) (throw (IllegalArgumentException. "Parameter may only contain one element."))
    (empty? body) nil
    :else (let [future (first params)]
            `(reify org.jboss.netty.channel.ChannelFutureListener
               (operationComplete [this# ^ChannelFuture ~future] ~@body)))))

(defn- handler [bootstrap ssl-handler-factory client-handle exception-handler]
  "Function to create a ChannelUpstreamHandler"
  (proxy [org.jboss.netty.channel.SimpleChannelUpstreamHandler] []
    (channelConnected [^ChannelHandlerContext ctx ^ChannelStateEvent event]
      (trace "channelConnected")
      (let [ssl-handler (-> ctx
        (.getPipeline)
        (.get SslHandler))]
        (.handshake ssl-handler)
        ))
    (channelDisconnected [^ChannelHandlerContext ctx ^ChannelStateEvent event]
      (trace "channelDisconnected" this))
    (messageReceived [^ChannelHandlerContext ctx ^MessageEvent event]
      (trace "messageReceived -" (.getMessage event))
      )
    (exceptionCaught [^ChannelHandlerContext ctx ^ExceptionEvent event]
      (trace (.getCause event) "exceptionCaught")
      (when exception-handler (exception-handler (.getCause event)))
      (-> event
        (.getChannel)
        (.close))
      )
    (channelClosed [^ChannelHandlerContext ctx ^ChannelStateEvent event]
      (trace "channelClosed")
      (let [new-handler (ssl-handler-factory)
            pipeline (.getPipeline ctx)
            ssl-handler (.replace pipeline SslHandler "ssl" new-handler)]
        (-> (.connect bootstrap) (.addListener (future-listener [f]
                                                 (swap! client-handle (fn [_] (.getChannel f))))))))))


(defn- create-ssl-handler-factory [ssl-engine-factory] (fn [] (SslHandler. (ssl-engine-factory))))

(defn- create-pipeline-factory [ssl-handler-factory protocoll-handler timer time-out]
  "Creates a pipeline factory"
  (reify
    ChannelPipelineFactory
    (getPipeline [this]
      (let [id-gen (AtomicInteger.)]
        (doto (Channels/pipeline)
          (.addLast "ssl" (ssl-handler-factory))
          (.addLast "encoder" (encoder id-gen))
          (.addLast "decoder" (decoder))
          (.addLast "timeout" (WriteTimeoutHandler. timer (int (if time-out time-out 300))))
          (.addLast "protocoll-handler" protocoll-handler)
          ))
      )
    )
  )


(defn- default-exception-handler [cause] (info cause "An exception occured while sending push notification to the server."))

(defn- connect [^InetSocketAddress address ^SSLContext ssl-context time-out boss-executor worker-executor exception-handler]
  "creates a netty Channel to connect to the server."
  (let [engine-factory (ssl-engine-factory ssl-context :use-client-mode true)
        bootstrap (-> (NioClientSocketChannelFactory.
                        boss-executor worker-executor) (ClientBootstrap.))
        ssl-handler-factory (create-ssl-handler-factory engine-factory)
        client-handle (atom nil)
        pipeline-factory (create-pipeline-factory ssl-handler-factory (handler bootstrap ssl-handler-factory client-handle
                                                                        exception-handler) (timer) time-out)
        bootstrap (doto bootstrap
      (.setOption "connectTimeoutMillis" 5000)
      (.setPipelineFactory pipeline-factory)
      (.setOption "remoteAddress" address))
        future (.connect bootstrap)
        channel (-> future (.awaitUninterruptibly) (.getChannel))
        ]
    (if (.isSuccess future)
      (do
        (swap! client-handle (fn [_] channel))
        client-handle)
      nil)))



(defprotocol Connection
  (is-connected? [this] "Determines is a connection is connected")
  (write-message [this message] "Writes a message")
  (disconnect [this] "Disconnects a connection from the server")
  )

(defprotocol Result
  (success? [this] "Determines if the send operation was a success.")
  (done? [this] "Checks if the operation already competed.")
  )

(defn success? [^ChannelFuture future] (when future (-> future (.awaitUninterruptibly) (.isSuccess))))

(defn create-connection [^InetSocketAddress address ^SSLContext ssl-context & {:keys [time-out boss-executor worker-executor exception-handler]
                                                                               :or {time-out 300
                                                                                    boss-executor (default-thread-pool)
                                                                                    worker-executor (default-thread-pool)
                                                                                    exception-handler default-exception-handler}}]
  "Creates a connection"
  (let [client-handle (connect address ssl-context time-out boss-executor worker-executor exception-handler)]
    (when client-handle
      (reify Connection
        (is-connected? [_] (when-let [channel @client-handle] (.isConnected channel)))
        (disconnect [_] (when-let [channel @client-handle] (.close channel)))
        (write-message [_ message] (when-let [channel @client-handle] (.write channel message)))))))

(defn send-message [^herolabs.apns.push.Connection connection ^String device-token message & {:keys [completed-listener]}]
  "Sends a message in the standard message format to the Apple push service"
  (when (and connection device-token message)
    (loop [[listener & rest] (if (sequential? completed-listener) completed-listener [completed-listener])
           future (.write-message connection (with-meta message {:device-token device-token}))]
      (if listener (recur rest (doto future (.addListener listener))) future))))

(defn send-enhanced-message [^herolabs.apns.push.Connection connection ^String device-token message]
  "Sends a message in the enhanced message format to the Apple push service"
  (when (and connection device-token message)
    (let [msg (with-meta message {:device-token device-token :format :enhanced})]
      (.write-message connection msg)
      )))

(defn dev-address []
  (InetSocketAddress. "gateway.sandbox.push.apple.com" 2195)
  )

(defn prod-address []
  (InetSocketAddress. "gateway.push.apple.com" 2195)
  )