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
                    (newThread [_ r] (let [name (str "apns-push-pool-" (swap! number inc))
                                           ^Thread t (Thread. group r name 0)]
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

(defn logging-close-handler [_ _ _]
  (fn [^ChannelHandlerContext ctx ^ChannelStateEvent event]
    (let [^Channel channel (.getChannel ctx)]
      (debug "Channel to" (if channel (.getRemoteAddress channel) "-") "was closed."))))

(defn reopening-close-handler [^ClientBootstrap bootstrap ssl-handler-factory client-handle]
  (fn reopening-channel-closed-handler [^ChannelHandlerContext ctx ^ChannelStateEvent event]
    (let [^Channel channel (.getChannel ctx)
          ^SslHandler new-handler (ssl-handler-factory)
          ^ChannelPipeline pipeline (.getPipeline ctx)
          ^SslHandler ssl-handler (.replace pipeline SslHandler "ssl" new-handler)]
      (debug "Channel to" (if channel (.getRemoteAddress channel) "-") "was closed. Reopening it ...")
      (-> (.connect bootstrap) (.addListener (future-listener [f]
                                               (swap! client-handle (fn [_] (.getChannel f)))))))))

(defn- handler
  "Function to create a ChannelUpstreamHandler"
  [^ClientBootstrap bootstrap ssl-handler-factory client-handle exception-handler close-handler]
  (proxy [org.jboss.netty.channel.SimpleChannelUpstreamHandler] []
    (channelConnected [^ChannelHandlerContext ctx ^ChannelStateEvent event]
      (let [^Channel channel (.getChannel ctx)
            ^SslHandler ssl-handler (-> ctx
                                      (.getPipeline)
                                      (.get SslHandler))]
        (.handshake ssl-handler)
        (debug "Channel connected to" (if channel (.getRemoteAddress channel) "-") ".")))
    (channelDisconnected [^ChannelHandlerContext ctx ^ChannelStateEvent event]
      (let [^Channel channel (.getChannel ctx)]
        (debug "Channel disconnected from" (if channel (.getRemoteAddress channel) "-") ".")))
    (messageReceived [^ChannelHandlerContext ctx ^MessageEvent event]
      (trace "messageReceived -" (.getMessage event)))
    (exceptionCaught [^ChannelHandlerContext ctx ^ExceptionEvent event]
      (let [^Channel channel (.getChannel ctx)]
        (debug (.getCause event) "An exception occured on channel to" (if channel (.getRemoteAddress channel) "-") ", reopening channel...")
        (when exception-handler (exception-handler (.getCause event)))
        (-> event
          (.getChannel)
          (.close))))
    (channelClosed [^ChannelHandlerContext ctx ^ChannelStateEvent event]
      (close-handler ctx event))))


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
          (.addLast "protocoll-handler" protocoll-handler))))))

(defn- default-exception-handler [cause] (info cause "An exception occured while sending push notification to the server."))

(defn- connect [^InetSocketAddress address ^SSLContext ssl-context time-out boss-executor worker-executor
                exception-handler close-handler]
  "Creates a Netty Channel to connect to the server."
  (let [engine-factory (ssl-engine-factory ssl-context :use-client-mode true)
        bootstrap (-> (NioClientSocketChannelFactory.
                        boss-executor worker-executor) (ClientBootstrap.))
        ssl-handler-factory (create-ssl-handler-factory engine-factory)
        client-handle (atom nil)
        pipeline-factory (create-pipeline-factory ssl-handler-factory
                           (handler bootstrap ssl-handler-factory client-handle
                             exception-handler
                             (close-handler bootstrap ssl-handler-factory client-handle))
                           (timer) time-out)
        bootstrap (doto bootstrap
                    (.setOption "connectTimeoutMillis" 5000)
                    (.setPipelineFactory pipeline-factory)
                    (.setOption "remoteAddress" address))
        future (.connect bootstrap)
        channel (-> future (.awaitUninterruptibly) (.getChannel))]
    (if (.isSuccess future)
      (do
        (swap! client-handle (fn [_] channel))
        client-handle)
      (let [cause (when future (.getCause future))]
        (warn cause "Unable to establish connection to" address "due to:" (if cause (.getMessage cause) "An unexpected cause."))
        nil))))



(defprotocol Connection
  (is-connected? [this] "Determines is a connection is connected")
  (write-message [this message] "Writes a message"))

(defprotocol Result
  (success? [this] "Determines if the send operation was a success.")
  (done? [this] "Checks if the operation already competed."))

(defn success?
  "Checks of a future finished successful. Also waits uninterruptibly until the future finished to determine the result."
  [^ChannelFuture future] (when future (-> future (.awaitUninterruptibly) (.isSuccess))))

(defn create-connection
  "Creates a connection the the Apple push notification service."
  [^InetSocketAddress address
   ^SSLContext ssl-context
   & {:keys [time-out boss-executor worker-executor exception-handler close-handler]
      :or {time-out 300
           boss-executor (default-thread-pool)
           worker-executor (default-thread-pool)
           exception-handler default-exception-handler
           close-handler reopening-close-handler}}]
  (let [client-handle (connect address ssl-context time-out boss-executor worker-executor exception-handler close-handler)]
    (when client-handle
      (reify Connection
        (is-connected? [_] (when-let [^Channel channel @client-handle] (.isConnected channel)))
        (write-message [_ message] (when-let [^Channel channel @client-handle] (.write channel message)))
        java.io.Closeable
        (close [this] (when-let [^Channel channel @client-handle] (.close channel)))))))

(defn send-message
  "Sends a message in the standard message format to the Apple push service"
  [^herolabs.apns.push.Connection connection ^String device-token message & {:keys [completed-listener]}]
  (when (and connection device-token message)
    (loop [[listener & rest] (if (sequential? completed-listener) completed-listener [completed-listener])
           ^ChannelFuture future (.write-message connection (with-meta message {:device-token device-token}))]
      (if listener (recur rest (doto future (.addListener listener))) future))))

(defn send-enhanced-message
  "Sends a message in the enhanced message format to the Apple push service"
  [^herolabs.apns.push.Connection connection ^String device-token message  & {:keys [completed-listener]}]
  (when (and connection device-token message)
    (loop [[listener & rest] (if (sequential? completed-listener) completed-listener [completed-listener])
           ^ChannelFuture future (.write-message connection (with-meta message {:device-token device-token :format :enhanced}))]
      (if listener (recur rest (doto future (.addListener listener))) future))))

(defn dev-address
  "The Apple sandbox address for the push service."
  []
  (InetSocketAddress. "gateway.sandbox.push.apple.com" 2195))

(defn prod-address
  "The productive Apple internet address for the push service."
  []
  (InetSocketAddress. "gateway.push.apple.com" 2195))
