(ns herolabs.apns.feedback
  (:use [clojure.tools.logging]
        [herolabs.apns.ssl :only (ssl-context ssl-engine)]
        [herolabs.apns.protocol :only (encoder decoder feedback-decoder)]
        [clojure.stacktrace])
  (:import [org.jboss.netty.channel Channel ChannelFuture Channels ChannelPipeline ChannelPipelineFactory ChannelEvent
            ChannelHandlerContext ChannelStateEvent ExceptionEvent MessageEvent WriteCompletionEvent
            SimpleChannelUpstreamHandler]
           [org.jboss.netty.channel.socket.nio NioClientSocketChannelFactory]
           [org.jboss.netty.bootstrap ClientBootstrap]
           [org.jboss.netty.handler.ssl SslHandler]
           [org.jboss.netty.handler.timeout ReadTimeoutHandler]
           [org.jboss.netty.util HashedWheelTimer]
           [java.util.concurrent Executors ExecutorService ThreadFactory]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]
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
                         (newThread [_ r] (let [t (Thread. group r (str "apns-feedback-" (swap! number inc)) 0)
                                                t (if (.isDaemon t) (.setDaemon t false) t)
                                                t (if (not= Thread/NORM_PRIORITY (.getPriority t)) (.setPriority t Thread/NORM_PRIORITY) t)]
                                            t)))))))))

(def ^:private timer* (ref nil))

(defn- timer [] (or @timer* (dosync (alter timer* (fn [_] (HashedWheelTimer.))))))


(defn- handler [^LinkedBlockingQueue queue]
  "Function to create a ChannelUpstreamHandler"
  (proxy [org.jboss.netty.channel.SimpleChannelUpstreamHandler] []
    (channelConnected [^ChannelHandlerContext ctx ^ChannelStateEvent event]
      (debug "channelConnected")
      (let [ssl-handler (-> ctx
                            (.getPipeline)
                            (.get SslHandler))]
        (.handshake ssl-handler)))
    (messageReceived [^ChannelHandlerContext ctx ^MessageEvent event]
      (debug "messageReceived - " (.getMessage event))
      (.put queue (.getMessage event)))
    (exceptionCaught [^ChannelHandlerContext ctx ^ExceptionEvent event]
      (debug (.getCause event) "exceptionCaught")
      (-> event
          (.getChannel)
          (.close)))
    (channelClosed [^ChannelHandlerContext ctx ^ChannelStateEvent event]
      (debug "channelClosed"))))



(defn- create-pipeline-factory [ssl-engine handler time-out]
  "Creates a pipeline factory"
  (reify
    ChannelPipelineFactory
    (getPipeline [this]
      (doto (Channels/pipeline)
        (.addLast "ssl" (SslHandler. ssl-engine))
        (.addLast "decoder" (feedback-decoder))
        (.addLast "timeout" (ReadTimeoutHandler. (timer) (int (if time-out time-out 300))))
        (.addLast "handler" handler)))))

(defn- connect [^InetSocketAddress address ^SSLContext ssl-context time-out queue boss-executor worker-executor]
  "creates a netty Channel to connect to the server."
  (try
    (let [engine (ssl-engine ssl-context :use-client-mode true)
          pipeline-factory (create-pipeline-factory engine (handler queue) time-out)
          bootstrap (doto (-> (NioClientSocketChannelFactory. boss-executor worker-executor)
                              (ClientBootstrap.))
                      (.setOption "connectTimeoutMillis" 5000)
                      (.setPipelineFactory pipeline-factory))
          future (.connect bootstrap address)
          channel (-> future
                      (.awaitUninterruptibly)
                      (.getChannel))]
      (if (.isSuccess future)
        channel
        (do
          (.releaseExternalResources bootstrap)
          nil)))
    (catch java.lang.Exception e
      (warn e "Error"))))


(defn- read-feedback [queue channel]
  "Internal function to create a lazy-seq returning the data from the feedback service"
  (lazy-seq
   (if-let [next (.poll queue 10 TimeUnit/SECONDS)]
     (cons next (read-feedback queue channel))
     (when (.isConnected channel)
       (.close channel)
       nil))))

(defn feedback [^InetSocketAddress address ^SSLContext ssl-context & {:keys [time-out boss-executor worker-executor]
                                                                      :or {time-out 300
                                                                           boss-executor (default-thread-pool)
                                                                           worker-executor (default-thread-pool)}}]
  "Creates a seq with the results from the feedback service"
  (let [queue (LinkedBlockingQueue.)
        channel (connect address ssl-context time-out queue boss-executor worker-executor)]
    (read-feedback queue channel)))

(defn dev-address [] (InetSocketAddress. "feedback.sandbox.push.apple.com" 2196))

(defn prod-address [] (InetSocketAddress. "feedback.push.apple.com" 2196))
