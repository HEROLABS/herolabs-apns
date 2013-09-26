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
                                  (byte 8) :invalid-token
                                  (byte 10) :shutdown})


(def ^:dynamic *coercions* json/*coercions*)

(def queue-entry-comparator (reify java.util.Comparator
                              (compare [_ a b]
                                (let [aid (first a)
                                      bid (first b)
                                      idcmp (compare aid bid)]
                                  (if (zero? idcmp)
                                    (if (= a b) 0 1)
                                    idcmp)))))

(defn- serialize [msg]
  "Serializes the map into a JSON representation"
  (binding [json/*coercions* *coercions*]
    (json/generate-string msg)))

(defn encode-frame
  "Encodes a frame from the messages supplied
  https://developer.apple.com/library/ios/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/Chapters/CommunicatingWIthAPS.html"
  [msg id expires priority]
  (let [meta-data (meta msg)]
    (if-let [token (:device-token meta-data)]
      (let [^bytes token (.decode hex-codec token)
            ^String serialized (serialize msg)
            payload (.getBytes serialized)
            expries (or (:expires meta-data) expires)
            priority (or (:priority meta-data) priority)
            buffer-size (+ (+ 1 2 32) ;token
                          (+ 1 2 (count payload)) ;payload
                          (+ 1 2 4) ;notification id
                          (if expires 7 0) ;expires field
                          (if priority 4 0)) ;priority field
            ^ByteBuf body-buffer (-> (Unpooled/buffer buffer-size)
                                   (.writeByte 1)
                                   (.writeShort 32)
                                   (.writeBytes token)
                                   (.writeByte 2)
                                   (.writeShort (count payload))
                                   (.writeBytes payload)
                                   (.writeByte 3)
                                   (.writeShort 4)
                                   (.writeInt id))
            body-buffer (if-not expires body-buffer (-> body-buffer
                                                      (.writeByte 4)
                                                      (.writeShort 4)
                                                      (.writeInt (int (cond
                                                                        (fn? expires) (expires)
                                                                        (type java.util.Date expires) (quot (.getTime expires) 1000)
                                                                        (integer? expires) expires
                                                                        (number? expires) (quot expires 1000)
                                                                        :else 0)))))
            body-buffer (if-not priority body-buffer (-> body-buffer
                                                       (.writeByte 5)
                                                       (.writeShort 1)
                                                       (.writeByte (byte (condp = priority
                                                                           :immediately 10
                                                                           :energy-efficient 5
                                                                           5)))))
            ^ByteBuf header-buffer (-> (Unpooled/buffer 5)
                                     (.writeByte (byte 2))
                                     (.writeInt (.readableBytes body-buffer)))
            frame-buffer (Unpooled/copiedBuffer (into-array ByteBuf [header-buffer body-buffer]))]
        frame-buffer)
      (throw (IllegalArgumentException. "Message must contain device-token as meta data.")))))

(defn codec [^AtomicInteger id-gen sent-queue expires priority]
  "Creates an encoder for the APNS protocol"
  (proxy [io.netty.handler.codec.MessageToMessageCodec] []
    (encode [^ChannelHandlerContext ctx msg ^List out]
      (let [id (.getAndIncrement id-gen)
            out (.add out (encode-frame msg id expires priority))
            sent (System/currentTimeMillis)]
        (swap! sent-queue (fn [queue] (conj queue [id sent msg])))
        out))
    (decode [^ChannelHandlerContext ctx ^ByteBuf msg ^List out]
      (let [command (.readByte msg)
            status (.readByte msg)
            id (.readInt msg)
            faulty-message (nth (first (filter #(let [i (first %)] (= i id)) @sent-queue)) 2)
            resent (swap! sent-queue (fn [msgs] (apply sorted-set-by queue-entry-comparator (filter #(let [i (first %)] (> i id)) msgs))))]
        (.add out {:status (get status-dictionary status :unknown ) :id id :faulty faulty-message :resent resent})))))


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
