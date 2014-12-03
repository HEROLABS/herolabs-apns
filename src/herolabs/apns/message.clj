(ns herolabs.apns.message)

(defn to
  "Attach a destination to the message by attaching the APS token."
  ([^String token] (to {} token))
  ([message ^String token] (with-meta message (assoc (meta message) :device-token token))))

(defn token [message] (:device-token (meta message)))

(defn priority [message] (:priority (meta message)))

(defn expires [message] (:expires (meta message)))


(defn with-priority
  "Sets the message priority"
  ([message priority] (with-meta message (assoc (meta message) :priority priority))))

(defn with-expires
  "Defines when the message expires. You may pass a date, a timestamp as long or a function that returns a timestamp."
  ([message expiry] (with-meta message (assoc (meta message) :expires expiry))))

(defn with-badge
  "Sets the number of badge."
  [message number] (assoc-in message [:aps :badge ] number))

(defn with-sound
  "Sets the sound-file."
  [message sound] (assoc-in message [:aps :sound ] (name sound)))

(defn with-standard-alert
  "Sets the standard alert value. you may pass a String or a map which complies to the APS message alert format."
  [message body]
  (assoc-in message [:aps :alert ] body))

(defn with-action-loc-key
  "Sets the :action-loc-key value."
  [message key] (if key
                  (assoc-in message [:aps :alert :action-loc-key ] key)
                  message))

(defn with-loc-key
  "Sets the :loc-key value."
  [message key] (if key
                  (assoc-in message [:aps :alert :loc-key ] key)
                  message))

(defn with-loc-args
  "Sets the :loc-args value."
  [message args] (if-not (empty? args)
                   (assoc-in message [:aps :alert :loc-args ] (if (sequential? args) args (list args)))
                   message))

(defn with-payload
  "Adds payload to the message."
  [message payload] (if-not (empty? payload)
                      (assoc-in message [:aps :payload] payload)
                      message))

(defn with-alert-body
  "Sets the alert body test."
  [message body] (if key
                   (assoc-in message [:aps :alert :body ] body)
                   message))
