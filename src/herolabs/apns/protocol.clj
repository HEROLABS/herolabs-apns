(ns herolabs.apns.protocol
  (:require [clj-json.core :as json])
  (:import [org.jboss.netty.channel Channel ChannelHandlerContext]
           [org.jboss.netty.buffer ChannelBuffer ChannelBuffers]
           [java.nio ByteOrder]
           [org.apache.commons.codec.binary Hex]
           [java.util.concurrent.atomic AtomicInteger]))

;; some constants
(def ^:private standard-head (byte-array 1 (byte 0)))
(def ^:private enhanced-head (byte-array 1 (byte 1)))
(def ^{:private true} hex-codec (Hex.))
(def ^:private status-dictionary {(byte 0) :ok
                                  (byte 1) :processing-error
                                  (byte 2) :missing-device-token
                                  (byte 3) :missing-topic
                                  (byte 4) :missing-payload
                                  (byte 5) :invalid-token-size
                                  (byte 6) :invalid-topic-size
                                  (byte 7) :invalid-payload-size
                                  (byte 8) :invalid-token})


(def ^:dynamic *coercions* json/*coercions*)

(defn- serialize [msg]
  "Serializes the map into a JSON representation"
  (binding [json/*coercions* *coercions*]
    (json/generate-string msg)))

(defn- dynamic-buffer [len]
  "Creates a dynamic buffer."
  (ChannelBuffers/dynamicBuffer ByteOrder/BIG_ENDIAN len))

(defn- encode-message [^String device-token msg]
  "Encodes a message into the standard APNS protocol format
  http://developer.apple.com/library/mac/#documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/CommunicatingWIthAPS/CommunicatingWIthAPS.html"
  (let [token (.decode hex-codec device-token)
        serialized (serialize msg)
        buffer (doto (dynamic-buffer (+ 1 2 (count token) 2 (count serialized)))
                 (.writeBytes standard-head)
                 (.writeShort (int (count token)))
                 (.writeBytes token)
                 (.writeShort (int (count serialized)))
                 (.writeBytes (.getBytes serialized)))]
    buffer))



(defn encode-enhanced-message [^AtomicInteger id-gen ^String device-token msg]
  "Encodes a message into the enhanced APNS protocol format
  http://developer.apple.com/library/mac/#documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/CommunicatingWIthAPS/CommunicatingWIthAPS.html"
  (let [token (.decode hex-codec device-token)
        m (meta msg)
        id (.getAndIncrement id-gen)
        expires (get m :expires Integer/MAX_VALUE)
        serialized (serialize msg)
        buffer (doto (dynamic-buffer (+ 1 4 4 2 (count token) 2 (count serialized)))
                 (.writeBytes enhanced-head)
                 (.writeInt id)
                 (.writeInt (int expires))
                 (.writeShort (int (count token)))
                 (.writeBytes token)
                 (.writeShort (int (count serialized)))
                 (.writeBytes (.getBytes serialized)))]
    buffer))



(defn encoder [^AtomicInteger id-gen]
  "Creates an encoder for the APNS protocol"
  (proxy [org.jboss.netty.handler.codec.oneone.OneToOneEncoder] []
    (encode [^ChannelHandlerContext ctx ^Channel channel msg]
      (if-not (associative? msg)
        (throw (IllegalArgumentException. "Only accociative datastructures may be send to the push notification service."))
        (if-let [device-token (get (meta msg) :device-token )]
          (if (= :enhanced (get (meta msg) :format ))
            (encode-enhanced-message id-gen device-token msg)
            (encode-message device-token msg))
          (throw (IllegalArgumentException. "Message must contain a :device-token as meta.")))))))


(defn decoder []
  "Creates an decoder for the APNS protocol."
  (proxy [org.jboss.netty.handler.codec.oneone.OneToOneDecoder] []
    (decode [^ChannelHandlerContext ctx ^Channel channel msg]
      (let [command (.readByte msg)
            status (.readByte msg)
            id (.readInt msg)]
        {:status (get status-dictionary status :unknown ) :id id}))))

(defn feedback-decoder []
  "Creates an decoder for the APNS protocol."
  (proxy [org.jboss.netty.handler.codec.oneone.OneToOneDecoder] []
    (decode [^ChannelHandlerContext ctx ^Channel channel msg]
      (let [time (* (.readInt msg) 1000)
            token-len (.readShort msg)
            token-bytes (byte-array token-len)]
        (.readBytes msg token-bytes)
        (let [token (Hex/encodeHexString token-bytes)]
          [token time])))))
