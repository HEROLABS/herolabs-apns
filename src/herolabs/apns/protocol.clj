(ns herolabs.apns.protocol
  (:require [clj-json.core :as json])
  (:use [clojure.tools.logging])
  (:import [java.util List]
           [io.netty.channel Channel ChannelHandlerContext]
           [io.netty.buffer ByteBuf ByteBufAllocator Unpooled ByteBufUtil]
           [java.nio ByteOrder]
           [org.apache.commons.codec.binary Hex]
           [java.util.concurrent.atomic AtomicInteger]))

;; some constants
(def ^{:private true :tag 'bytes} standard-head (byte-array 1 (byte 0)))
(def ^{:private true :tag 'bytes} enhanced-head (byte-array 1 (byte 1)))
(def ^{:private true :tag 'Hex} hex-codec (Hex.))
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

(defn encode-item [^ByteBuf buffer number ^String token msg id expires priority]

  (let [^bytes token (.decode hex-codec token)
        ^String serialized (serialize msg)
        payload (.getBytes serialized)
        item-length (+ (count token) (count payload) 4 4 1)]
    (info "number:" number "token:" (count token) "payload:" (count payload) "id:" id "->" item-length)
    (info "msg:" serialized " (" (count payload) ")")
    (if (> (count payload) 255)
      (do (warn "Message with" (count payload) "bytes to long:" serialized) buffer)
      (-> buffer
        (.writeByte (byte number))
        (.writeShort (short item-length))
        (.writeBytes token)
        (.writeBytes payload)
        (.writeInt (int id))
        (.writeInt (int (or expires (+ 3600 (quot (System/currentTimeMillis)1000 )))))
        (.writeByte (byte (condp = priority
                            :immediately 10
                            :energy-efficient 5
                            10)))))))

(defn encode-frame
  "Encodes a frame from the messages supplied
  https://developer.apple.com/library/ios/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/Chapters/CommunicatingWIthAPS.html"
  [messages expires priority ^AtomicInteger id-gen]
  (let [^ByteBuf body-buffer (loop [messages messages
                                    ^ByteBuf buffer (Unpooled/buffer (* (count messages) 256))
                                    number 1]
                               (if-let [msg (first messages)]
                                 (if-let [token (:device-token (meta msg))]
                                   (if-not (associative? msg)
                                     (do
                                       (warn "Unsupported message format on message number" number ", message will be skipped.")
                                       (recur (rest messages) buffer number))
                                     (recur
                                       (rest messages)
                                       (encode-item buffer number token msg (.getAndIncrement id-gen) (or (:expires (meta msg)) expires) (or (:priority (meta msg)) priority))
                                       (inc number)))
                                   (recur (rest messages) buffer number))
                                 buffer)
                               )
        ^ByteBuf header-buffer (-> (Unpooled/buffer 5)
                                 (.writeByte (byte 2))
                                 (.writeInt (spy (.readableBytes body-buffer))))
        frame-buffer (Unpooled/copiedBuffer (into-array ByteBuf [header-buffer body-buffer]))]
    (info (ByteBufUtil/hexDump header-buffer))
    (info (ByteBufUtil/hexDump body-buffer))
    #_ (info (ByteBufUtil/hexDump frame-buffer))
    frame-buffer
    ))

(defn codec [^AtomicInteger id-gen expires priority]
  "Creates an encoder for the APNS protocol"
  (proxy [io.netty.handler.codec.MessageToMessageCodec] []
    (encode [^ChannelHandlerContext ctx msgs ^List out]
      (let [meta-data (meta msgs)]
        (cond
          (sequential? msgs) (.add out (encode-frame msgs (or (:expires meta-data) expires) (or (:priority meta-data) priority) id-gen))
          (associative? msgs) (.add out (encode-frame [msgs] (or (:expires meta-data) expires) (or (:priority meta-data) priority) id-gen))
          :otherwise (throw (IllegalArgumentException. "Unrecognized message format:" (type msgs)))
          )))
    (decode [^ChannelHandlerContext ctx ^ByteBuf msg ^List out]
      (let [command (.readByte msg)
            status (.readByte msg)
            id (.readInt msg)]
        (info "status:" status ", id:" id)
        (.add out {:status (get status-dictionary status :unknown ) :id id})))))


(defn feedback-decoder []
  "Creates an decoder for the APNS protocol."
  (proxy [io.netty.handler.codec.MessageToMessageDecoder] []
    (decode [^ChannelHandlerContext ctx ^ByteBuf msg ^List out]
      (let [time (* (.readInt msg) 1000)
            token-len (.readShort msg)
            token-bytes (byte-array token-len)]
        (.readBytes msg token-bytes)
        (let [token (Hex/encodeHexString token-bytes)]
          (.add out [token time]))))))
